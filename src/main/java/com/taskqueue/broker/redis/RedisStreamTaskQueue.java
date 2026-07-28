package com.taskqueue.broker.redis;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.model.Task;
import com.taskqueue.port.AckableTaskQueue;

/**
 * {@link AckableTaskQueue} backed by Redis Streams.
 *
 * <p>From task-queues.md + message-queues.md: Redis Streams provide persistent,
 * append-only logs with consumer groups. Each worker reads from the same consumer
 * group — Redis assigns entries exclusively so no two workers process the same entry
 * concurrently.
 *
 * <p>Key operations:
 * <ul>
 *   <li>{@code XADD} — enqueue a task (append to stream)</li>
 *   <li>{@code XREADGROUP} — poll (take a lease, entry moves to PEL)</li>
 *   <li>{@code XACK}  — confirm processing (remove from PEL)</li>
 *   <li>{@code XAUTOCLAIM} — reclaim abandoned PEL entries from dead workers</li>
 * </ul>
 *
 * <p>From broker-comparison.md: Redis Streams chosen over Kafka here because:
 * at-least-once semantics, consumer groups with PEL, no ZooKeeper/brokers to operate,
 * single Redis instance already in the infrastructure for rate-limiting and locking.
 */
public class RedisStreamTaskQueue implements AckableTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamTaskQueue.class);

    /** Stream key in Redis — all tasks flow through this stream. */
    static final String STREAM_KEY = "task-stream";
    static final String FIELD_PAYLOAD = "payload";
    static final String FIELD_TYPE    = "type";
    static final String FIELD_ID      = "id";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String consumerGroup;
    private final String consumerName;

    public RedisStreamTaskQueue(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            String consumerGroup,
            String consumerName) {
        this.redis         = redis;
        this.mapper        = mapper.copy()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.GETTER,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.IS_GETTER,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE);
        this.consumerGroup = consumerGroup;
        this.consumerName  = consumerName;
        ensureConsumerGroup();
    }

    // ── TaskQueue ─────────────────────────────────────────────────────────────

    /**
     * XADD: appends the task to the stream.
     * The stream stores three fields per entry: id, type, and full JSON payload.
     */
    @Override
    public void enqueue(Task task) {
        try {
            String json = mapper.writeValueAsString(task);
            redis.opsForStream().add(
                    MapRecord.create(STREAM_KEY, Map.of(
                            FIELD_ID,      task.id(),
                            FIELD_TYPE,    task.type(),
                            FIELD_PAYLOAD, json)));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize task " + task.id(), e);
        }
    }

    /**
     * Blocking dequeue — wraps {@link #poll(Duration)} for TaskQueue compatibility.
     * Blocks up to 1 second per attempt so the worker can check its shutdown flag.
     */
    @Override
    public Task dequeue() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            Optional<Lease> lease = poll(Duration.ofSeconds(1));
            if (lease.isPresent()) {
                // Auto-ack for legacy Worker that doesn't handle leases
                Task task = lease.get().task();
                ack(lease.get());
                return task;
            }
        }
        throw new InterruptedException("Worker interrupted during dequeue");
    }

    @Override
    public int size() {
        Long len = redis.opsForStream().size(STREAM_KEY);
        return len == null ? 0 : len.intValue();
    }

    // ── AckableTaskQueue ──────────────────────────────────────────────────────

    /**
     * XREADGROUP: claim up to 1 entry from the consumer group.
     *
     * <p>Blocks up to {@code timeout} for a new entry. Returns empty on timeout
     * (allows the caller's loop to check shutdown flag).
     */
    @SuppressWarnings("unchecked")
    @Override
    public Optional<Lease> poll(Duration timeout) throws InterruptedException {
        try {
            StreamReadOptions opts = StreamReadOptions.empty()
                    .count(1)
                    .block(timeout);

            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    opts,
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

            if (records == null || records.isEmpty()) {
                return Optional.empty();
            }

            MapRecord<String, Object, Object> record = records.get(0);
            Task task = deserialize(record);
            return Optional.of(new Lease(record.getId().getValue(), task));

        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted during Redis poll");
            }
            log.warn("[{}] poll error: {}", consumerName, e.getMessage());
            ensureConsumerGroup();
            return Optional.empty();
        }
    }

    /** XACK: confirms the lease — removes the entry from the consumer's PEL. */
    @Override
    public void ack(Lease lease) {
        try {
            redis.opsForStream().acknowledge(STREAM_KEY, consumerGroup,
                    RecordId.of(lease.entryId()));
        } catch (Exception e) {
            log.error("[{}] XACK failed for entry {}: {}", consumerName, lease.entryId(), e.getMessage());
        }
    }

    /**
     * NACK: leaves the entry in the PEL. After the visibility timeout, {@link #reclaimStale}
     * will re-assign it to an active consumer.
     */
    @Override
    public void nack(Lease lease, String reason) {
        log.warn("[{}] NACK entry={} reason={}", consumerName, lease.entryId(), reason);
        // No explicit XNACK in Redis — entry stays in PEL until reclaimed.
    }

    /**
     * XAUTOCLAIM: reassigns PEL entries idle longer than {@code visibilityTimeout}
     * to this consumer so they are re-delivered.
     *
     * <p>From sharding.md: "XAUTOCLAIM is the atomic way to steal idle entries
     * from a dead consumer's PEL. It updates the delivery count so you can detect
     * poison messages."
     */
    @Override
    public int reclaimStale(Duration visibilityTimeout, int maxCount) {
        try {
            long minIdleMs = visibilityTimeout.toMillis();
            int reclaimed = claimStaleViaXClaim(minIdleMs, maxCount);
            if (reclaimed > 0) {
                log.info("[{}] Reclaimed {} stale PEL entries", consumerName, reclaimed);
            }
            return reclaimed;
        } catch (Exception e) {
            log.warn("[{}] reclaimStale error: {}", consumerName, e.getMessage());
            return 0;
        }
    }

    /**
     * Reclaim using XPENDING + XCLAIM via StreamOperations.
     * Fetches PEL entries idle > minIdleMs and re-assigns them to this consumer.
     */
    @SuppressWarnings("unchecked")
    private int claimStaleViaXClaim(long minIdleMs, int maxCount) {
        try {
            var pending = redis.opsForStream().pending(STREAM_KEY, consumerGroup,
                    org.springframework.data.domain.Range.unbounded(), (long) maxCount);
            if (pending == null) return 0;

            int count = 0;
            for (var msg : pending) {
                if (msg.getElapsedTimeSinceLastDelivery().toMillis() >= minIdleMs) {
                    redis.opsForStream().claim(
                            STREAM_KEY,
                            consumerGroup,
                            consumerName,
                            Duration.ofMillis(minIdleMs),
                            msg.getId());
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            log.warn("[{}] claimStaleViaXClaim error: {}", consumerName, e.getMessage());
            return 0;
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * Ensures the consumer group exists. If the stream doesn't exist yet,
     * creates it with {@code MKSTREAM} so the first XREADGROUP doesn't fail.
     */
    private void ensureConsumerGroup() {
        try {
            redis.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), consumerGroup);
            log.info("[{}] Consumer group '{}' created on stream '{}'",
                    consumerName, consumerGroup, STREAM_KEY);
        } catch (Exception e) {
            // Group already exists — this is expected on restart
            log.debug("[{}] Consumer group '{}' already exists: {}",
                    consumerName, consumerGroup, e.getMessage());
        }
    }

    private Task deserialize(MapRecord<String, Object, Object> record) {
        try {
            String json = (String) record.getValue().get(FIELD_PAYLOAD);
            return mapper.readValue(json, Task.class);
        } catch (Exception e) {
            log.error("[{}] Deserialization failed for entry {}: {}", consumerName, record.getId(), e.getMessage(), e);
            try {
                redis.opsForStream().acknowledge(STREAM_KEY, consumerGroup, record.getId());
            } catch (Exception ackEx) {
                // Ignore ack errors on failed entries
            }
            throw new RuntimeException("Failed to deserialize task from stream entry "
                    + record.getId(), e);
        }
    }
}
