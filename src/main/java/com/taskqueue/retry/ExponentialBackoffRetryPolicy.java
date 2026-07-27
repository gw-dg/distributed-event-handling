package com.taskqueue.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter and a cap.
 *
 * <p>From retries.md: "Naive retries do not add resilience. They add a positive
 * feedback loop." This implementation addresses the thundering herd problem using
 * the full-jitter algorithm recommended by AWS and described in retries.md.
 *
 * <h2>Algorithm</h2>
 * <pre>
 *   raw_delay = base * 2^(attempt - 1)
 *   capped    = min(raw_delay, maxDelay)
 *   if jitter: actual_delay = random(0, capped)   // full jitter
 *   else:      actual_delay = capped
 * </pre>
 *
 * <p>Full jitter spreads retries randomly across the delay window. When hundreds
 * of tasks fail simultaneously (e.g., a downstream outage), jitter ensures they
 * don't all retry at the same millisecond.
 *
 * <p>Example: base=1s, maxDelay=1m, maxAttempts=5, jitter=true →
 * <pre>
 *   attempt 1 → range [0s, 1s]   (2^0 = 1s capped)
 *   attempt 2 → range [0s, 2s]   (2^1 = 2s capped)
 *   attempt 3 → range [0s, 4s]
 *   attempt 4 → range [0s, 8s]
 *   attempt 5 → Optional.empty() (maxAttempts exceeded)
 * </pre>
 */
public final class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final int maxAttempts;
    private final boolean jitter;

    /**
     * @param baseDelay   base delay for attempt 1; doubles each retry
     * @param maxDelay    cap on any single retry delay
     * @param maxAttempts total attempts allowed (including the first)
     * @param jitter      if true, applies full jitter to prevent thundering herd
     */
    public ExponentialBackoffRetryPolicy(
            Duration baseDelay,
            Duration maxDelay,
            int maxAttempts,
            boolean jitter) {

        this.baseDelay   = Objects.requireNonNull(baseDelay,  "baseDelay");
        this.maxDelay    = Objects.requireNonNull(maxDelay,   "maxDelay");
        this.maxAttempts = maxAttempts;
        this.jitter      = jitter;

        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= baseDelay");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }

    @Override
    public Optional<Duration> nextDelay(int attempt) {
        if (attempt >= maxAttempts) {
            return Optional.empty();
        }

        // Exponential growth, capped at maxDelay
        // Use Math.min on millis to avoid Duration overflow on large exponents
        long baseMs = baseDelay.toMillis();
        long maxMs  = maxDelay.toMillis();

        // 2^(attempt-1) — clamp the exponent to avoid long overflow
        int exp = Math.min(attempt - 1, 62);  // 2^62 is safe in long arithmetic
        long rawMs = baseMs << exp;            // baseMs * 2^exp

        // Overflow guard: if rawMs went negative or exceeds max, use maxMs
        long cappedMs = (rawMs <= 0 || rawMs > maxMs) ? maxMs : rawMs;

        long delayMs = jitter
                ? ThreadLocalRandom.current().nextLong(cappedMs + 1)  // [0, capped]
                : cappedMs;

        return Optional.of(Duration.ofMillis(delayMs));
    }
}
