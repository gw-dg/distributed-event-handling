package com.taskqueue.retry;

import java.time.Duration;
import java.util.Optional;

/**
 * Strategy for deciding whether and when to retry a failed task.
 *
 * <p>From strategy.md: the Worker is the Context, RetryHandler is an intermediary,
 * and this interface is the Strategy. Concrete implementations ({@link FixedDelayRetryPolicy},
 * {@link ExponentialBackoffRetryPolicy}) are swapped in without touching the Worker.
 *
 * <p>From retries.md: "Pull the timing decision out of the worker and behind the
 * canonical RetryPolicy interface, and stop blocking — instead of Thread.sleep,
 * re-enqueue the task with a future scheduledAt so the queue owns the delay."
 *
 * <p>The interface is intentionally minimal — one method, one concern.
 * The OCP seam (solid.md): adding a new backoff strategy means a new class,
 * not a change to this interface or the Worker.
 */
public interface RetryPolicy {

    /**
     * Computes the delay before the next retry attempt.
     *
     * @param attempt the number of attempts already completed (1 = first attempt just failed)
     * @return the delay to wait before retrying, or {@code Optional.empty()} to stop retrying
     */
    Optional<Duration> nextDelay(int attempt);
}
