package com.taskqueue.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Simplest possible {@link RetryPolicy}: the same delay every time.
 *
 * <p>Useful for testing and simple scenarios. For production, prefer
 * {@link ExponentialBackoffRetryPolicy} to avoid thundering herd.
 *
 * <p>From retries.md: "A first, honest implementation: fixed delay with a
 * maximum attempt count."
 *
 * <p>Example: delay=5s, maxAttempts=3 →
 * <pre>
 *   attempt 1 failed -> nextDelay(1) -> Optional[5s]
 *   attempt 2 failed -> nextDelay(2) -> Optional[5s]
 *   attempt 3 failed -> nextDelay(3) -> Optional.empty() (exhausted)
 * </pre>
 */
public final class FixedDelayRetryPolicy implements RetryPolicy {

    private final Duration delay;
    private final int maxAttempts;

    /**
     * @param delay       fixed delay between attempts; must be positive
     * @param maxAttempts total attempts allowed (including the first); must be >= 1
     */
    public FixedDelayRetryPolicy(Duration delay, int maxAttempts) {
        if (Objects.requireNonNull(delay, "delay").isNegative() || delay.isZero()) {
            throw new IllegalArgumentException("delay must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.delay = delay;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public Optional<Duration> nextDelay(int attempt) {
        if (attempt >= maxAttempts) {
            return Optional.empty();  // budget exhausted
        }
        return Optional.of(delay);
    }
}
