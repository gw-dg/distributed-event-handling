package com.taskqueue.retry;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.taskqueue.common.Result;
import com.taskqueue.dlq.DeadLetterQueue;
import com.taskqueue.model.Task;
import com.taskqueue.repo.TaskRepository;

/**
 * Orchestrates retry decisions after a task execution fails.
 *
 * <p>The Worker delegates here on any failure. This class decides:
 * <ol>
 *   <li>Is the failure retryable? (from Result metadata)
 *   <li>Does the retry policy give us a next delay? (from attempts count)
 *   <li>If yes: persist RETRYING with a future scheduledAt and return.
 *   <li>If no: send to the Dead Letter Queue, persist DEAD, and return.
 * </ol>
 *
 * <p>From retries.md: "Stop blocking — instead of Thread.sleep, re-enqueue
 * the task with a future scheduledAt so the queue owns the delay."
 * The Worker thread is never blocked waiting for a retry delay.
 *
 * <p>From retries.md: "Retry logic is welded into the worker [in the naive version].
 * You cannot test it, swap it per task type, or reason about it independently."
 * Here it is decoupled: Worker → RetryHandler → RetryPolicy.
 */
@Component
public final class RetryHandler {

    private final RetryPolicy retryPolicy;
    private final TaskRepository repository;
    private final DeadLetterQueue deadLetterQueue;

    public RetryHandler(
            RetryPolicy retryPolicy,
            TaskRepository repository,
            DeadLetterQueue deadLetterQueue) {
        this.retryPolicy    = retryPolicy;
        this.repository     = repository;
        this.deadLetterQueue = deadLetterQueue;
    }

    /**
     * Handles the outcome of a failed task execution.
     *
     * @param task   the task that failed (in RUNNING state)
     * @param result the failure result from the handler
     */
    public void handleFailure(Task task, Result<Void> result) {
        String reason = result.errorMessage();
        boolean retryable = result.isRetryable();

        if (!retryable) {
            // Non-retryable failure: go straight to dead letter
            sendToDlq(task, reason);
            return;
        }

        // Check retry budget
        Optional<Duration> delay = retryPolicy.nextDelay(task.attempts());

        if (delay.isEmpty()) {
            // Retry budget exhausted
            sendToDlq(task, "Retry budget exhausted after " + task.attempts() + " attempts: " + reason);
            return;
        }

        // Schedule the retry
        Instant retryAt = Instant.now().plus(delay.get());
        Task retrying = task.scheduleRetryAt(retryAt, reason);
        repository.save(retrying);

        System.out.printf("[RetryHandler] Task %s scheduled for retry #%d in %s at %s%n",
                task.id(), task.attempts() + 1, delay.get(), retryAt);
    }

    /**
     * Handles an unexpected exception thrown by a handler.
     * Exceptions are treated as retryable by default (ch04: transient faults).
     *
     * @param task      the task that threw
     * @param exception the exception
     */
    public void handleException(Task task, Exception exception) {
        String reason = exception.getMessage() != null
                ? exception.getMessage()
                : exception.getClass().getSimpleName();

        // Exceptions are retryable (might be network glitch, OOM, etc.)
        handleFailure(task, Result.retryable(reason));
    }

    private void sendToDlq(Task task, String reason) {
        Task dead = task.recordFailure(reason, false);  // transitions to DEAD
        repository.save(dead);
        deadLetterQueue.send(dead, reason);
        System.out.printf("[RetryHandler] Task %s sent to DLQ: %s%n", task.id(), reason);
    }
}
