package com.taskqueue.outbox;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.domain.TaskEvent;
import com.taskqueue.port.EventBus;
import com.taskqueue.port.LeaderElector;
import com.taskqueue.queue.TaskQueue;

/**
 * Leader-elected outbox relay — bridges Postgres (durable) → broker (live).
 *
 * <p>From distributed-transactions-and-event-sourcing.md + leader-election.md:
 * "The relay is a singleton job. Only the leader node runs it. If the leader dies
 * mid-run, its lease expires; another node acquires leadership and processes the
 * same rows again. The outbox row is idempotent because the broker's consumer group
 * deduplicates via task id."
 *
 * <p>Algorithm each tick:
 * <ol>
 *   <li>Check {@link LeaderElector#isLeader("outbox-relay")} — bail if not leader.</li>
 *   <li>Open a transaction; fetch up to {@code batchSize} unpublished rows
 *       ({@code FOR UPDATE SKIP LOCKED}).</li>
 *   <li>For each row: enqueue the task onto the broker queue.</li>
 *   <li>Mark the row {@code published_at = now()}.</li>
 *   <li>Commit the transaction.</li>
 *   <li>Publish a {@code SUBMITTED} event to the {@link EventBus}.</li>
 * </ol>
 *
 * <p>Steps 2–5 are in one transaction so publish-and-mark is atomic. If the JVM
 * crashes after enqueue but before markPublished, the row is re-processed on the
 * next tick — at-least-once delivery is correct here; workers handle duplicates
 * via {@link com.taskqueue.worker.IdempotentHandler}.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String RELAY_ROLE = "outbox-relay";

    private final OutboxRepository outboxRepository;
    private final TaskQueue brokerQueue;
    private final EventBus eventBus;
    private final LeaderElector leaderElector;
    private final ObjectMapper mapper;
    private final int batchSize;

    public OutboxRelay(
            OutboxRepository outboxRepository,
            TaskQueue brokerQueue,
            EventBus eventBus,
            LeaderElector leaderElector,
            ObjectMapper mapper,
            int batchSize) {
        this.outboxRepository = outboxRepository;
        this.brokerQueue      = brokerQueue;
        this.eventBus         = eventBus;
        this.leaderElector    = leaderElector;
        this.mapper           = mapper;
        this.batchSize        = batchSize;
    }

    /**
     * Polls unpublished outbox rows and forwards them to the broker.
     * Rate: every 250ms (configurable via {@code taskqueue.outbox.poll-interval}).
     */
    @Scheduled(fixedDelayString = "${taskqueue.outbox.poll-interval:250}")
    @Transactional
    public void relay() {
        if (!leaderElector.isLeader(RELAY_ROLE)) {
            return;   // only the leader relays
        }

        List<OutboxRecord> rows = outboxRepository.fetchUnpublished(batchSize);
        if (rows.isEmpty()) {
            return;
        }

        int published = 0;
        for (OutboxRecord row : rows) {
            try {
                TaskEvent event = mapper.readValue(row.payload(), TaskEvent.class);
                // 1. Enqueue to broker
                brokerQueue.enqueue(taskFromEvent(event));
                // 2. Mark published (in same transaction)
                outboxRepository.markPublished(row.id());
                published++;
                // 3. Fire SUBMITTED event (out-of-transaction, best-effort)
                eventBus.publish(TaskEvent.submitted(row.aggregateId(), event.taskType()));
            } catch (Exception e) {
                log.error("[OutboxRelay] Failed to relay row id={} aggregateId={}: {}",
                        row.id(), row.aggregateId(), e.getMessage());
                // Row stays unpublished — will be retried next tick
            }
        }

        log.info("[OutboxRelay] Published {}/{} outbox rows", published, rows.size());
    }

    /**
     * Reconstructs a minimal Task from the event payload for broker enqueue.
     * The task will be looked up from Postgres by the worker before execution.
     */
    private com.taskqueue.model.Task taskFromEvent(TaskEvent event) {
        return com.taskqueue.model.Task.create(
                event.taskId(),
                event.taskType(),
                "{}",         // payload will be reloaded from DB by the worker
                5,            // maxAttempts default — worker reloads from DB
                0);
    }
}
