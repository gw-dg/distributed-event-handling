package com.taskqueue.broker.redis;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.dlq.DeadLetterQueue;
import com.taskqueue.model.Task;

/**
 * Dead Letter Queue backed by a separate Redis Stream ({@code dlq-stream}).
 *
 * <p>From dead-letter-queues.md: "The DLQ must survive worker crashes. In a
 * distributed system, a Postgres {@code dead_letter} table gives durability within
 * a single region. A Redis Stream DLQ adds cross-node visibility — any node can
 * {@code XREAD} the DLQ to inspect or replay failures."
 *
 * <p>This implementation replaces the Phase 3 {@link com.taskqueue.dlq.PostgresDeadLetterQueue}
 * for cross-node visibility. Both can coexist: inject whichever the configuration selects.
 *
 * <p>Operators consume the DLQ stream with:
 * <pre>
 *   redis-cli XREAD COUNT 100 STREAMS dlq-stream 0-0
 * </pre>
 *
 * <p>To replay a dead task: read the payload from the stream, re-submit via the REST API.
 */
public class RedisStreamDeadLetterQueue implements DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamDeadLetterQueue.class);

    static final String DLQ_STREAM = "dlq-stream";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisStreamDeadLetterQueue(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis  = redis;
        this.mapper = mapper.copy()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.GETTER,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.IS_GETTER,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE);
    }

    /**
     * Appends the dead task to the {@code dlq-stream}.
     *
     * <p>Per the {@link DeadLetterQueue} contract: MUST NOT throw — logs and swallows.
     */
    @Override
    public void send(Task task, String reason) {
        try {
            String json = mapper.writeValueAsString(task);
            redis.opsForStream().add(
                    MapRecord.create(DLQ_STREAM, Map.of(
                            "task_id", task.id(),
                            "task_type", task.type(),
                            "reason",  reason,
                            "payload", json)));
            log.info("[DLQ-Redis] task={} type={} reason={}", task.id(), task.type(), reason);
        } catch (JsonProcessingException e) {
            log.error("[DLQ-Redis] JSON serialization failed for task {}: {}", task.id(), e.getMessage());
        } catch (Exception e) {
            log.error("[DLQ-Redis] Failed to write task {} to dlq-stream: {}", task.id(), e.getMessage());
        }
    }
}
