package com.taskqueue.dlq;

import com.taskqueue.model.Task;

/**
 * Outbound port for the Dead Letter Queue.
 *
 * <p>From dead-letter-queues.md: tasks that exhaust their retry budget or
 * encounter non-retryable failures are sent here. Operators inspect the DLQ
 * to understand what failed and why, and to replay or discard messages.
 *
 * <p>Phase 2 implementation: {@link LoggingDeadLetterQueue} — logs to stdout.
 * Phase 3 will replace this with {@code PostgresDeadLetterQueue} that persists
 * the {@code dead_letter} table (V3 migration).
 *
 * <p>The port is kept here so the RetryHandler and Worker depend on this
 * interface, not on any specific storage. Phase 3 only swaps the bean binding
 * in {@code QueueConfig}.
 */
public interface DeadLetterQueue {

    /**
     * Receives a dead task with the reason it was discarded.
     *
     * <p>Implementations MUST NOT throw exceptions — a DLQ send failure must not
     * propagate back to the worker loop. Log and swallow any errors.
     *
     * @param task   the task that has been permanently failed; never null
     * @param reason human-readable explanation of why the task was dead-lettered
     */
    void send(Task task, String reason);
}
