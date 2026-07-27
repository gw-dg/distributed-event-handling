package com.taskqueue.dlq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.taskqueue.model.Task;
import com.taskqueue.repo.DeadLetterRepository;

/**
 * Durable Dead Letter Queue backed by the PostgreSQL {@code dead_letter} table.
 *
 * <p>Phase 3 replacement for {@link LoggingDeadLetterQueue}.
 *
 * <p>From dead-letter-queues.md:
 *   "A log-only DLQ is useful in development but useless in production.
 *    Production DLQs must be queryable, replayable, and alertable."
 *
 * <p>Contract: this class MUST NOT throw. Any persistence failure is logged and
 * swallowed — a DLQ write failure must never propagate back to the worker loop.
 * A worker that dies trying to record a failure is worse than losing the DLQ record.
 */
public class PostgresDeadLetterQueue implements DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(PostgresDeadLetterQueue.class);

    private final DeadLetterRepository repository;

    public PostgresDeadLetterQueue(DeadLetterRepository repository) {
        this.repository = repository;
    }

    /**
     * Persists a dead task to the {@code dead_letter} table.
     *
     * <p>Catches and logs all exceptions — DLQ writes are best-effort.
     * If the DB is down when a task is dead-lettered, we lose the DLQ entry
     * but the worker loop stays alive.
     *
     * @param task   the exhausted or non-retryable task
     * @param reason human-readable explanation
     */
    @Override
    public void send(Task task, String reason) {
        try {
            repository.insert(
                    task.id(),
                    task.type(),
                    task.payload(),
                    reason,
                    task.attempts(),
                    task.priority());

            log.warn("Task {} (type={}) dead-lettered after {} attempts. Reason: {}",
                    task.id(), task.type(), task.attempts(), reason);

        } catch (Exception ex) {
            // Log and swallow — never let DLQ failure propagate to the worker loop.
            log.error("Failed to persist dead-letter entry for task {} — entry lost: {}",
                    task.id(), ex.getMessage(), ex);
        }
    }
}
