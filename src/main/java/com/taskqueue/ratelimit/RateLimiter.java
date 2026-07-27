package com.taskqueue.ratelimit;

/**
 * Port interface for a rate limiter.
 *
 * <p>From rate-limiting.md: "A rate limiter is a policy decision: decide whether
 * the current request is allowed given the current consumption state. The policy
 * is completely separate from what happens when the request is denied (reject, delay,
 * shed load, etc.)."
 *
 * <p>Implementations are expected to be thread-safe — multiple worker/request
 * threads call {@link #tryAcquire()} concurrently.
 *
 * <p>Phase 3 implementation: {@link TokenBucketRateLimiter}.
 */
public interface RateLimiter {

    /**
     * Attempts to acquire one token.
     *
     * @return {@code true} if the request is allowed; {@code false} if the
     *         rate limit has been exceeded and the caller should be rejected
     */
    boolean tryAcquire();

    /**
     * Returns the number of tokens currently available.
     * Used by the HTTP filter to populate the {@code X-RateLimit-Remaining} header.
     */
    long available();

    /**
     * Returns the maximum number of tokens (bucket capacity).
     * Used to populate the {@code X-RateLimit-Limit} header.
     */
    long capacity();
}
