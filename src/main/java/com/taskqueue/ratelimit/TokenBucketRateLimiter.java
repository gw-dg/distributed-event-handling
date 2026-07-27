package com.taskqueue.ratelimit;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token Bucket rate limiter — thread-safe, lazy-refill, no background thread.
 *
 * <p>From rate-limiting.md: "Token Bucket is the dominant algorithm for burst-aware
 * rate limiting. Tokens accumulate at a steady refill rate (up to a max capacity).
 * Each request consumes one token. When the bucket is empty, requests are rejected."
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><b>Lazy refill</b> — tokens are added at call time based on elapsed wall clock,
 *       not by a background thread. This means zero overhead between requests.
 *   <li><b>ReentrantLock</b> — both tryAcquire and refill modify shared state atomically.
 *       A single lock is sufficient; contention is negligible at typical task submission rates.
 *   <li><b>Capacity cap</b> — tokens can never exceed capacity, preventing "sleeping burst"
 *       (a period of inactivity followed by a huge burst consuming all accumulated tokens).
 * </ul>
 *
 * <h2>Example config (application.yml)</h2>
 * <pre>
 * taskqueue:
 *   rate-limit:
 *     capacity: 100        # bucket size
 *     refill-rate: 10      # tokens per second
 * </pre>
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final long capacity;
    private final double tokensPerNano;     // tokens added per nanosecond

    private double tokens;
    private long lastRefillNanos;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Constructs a Token Bucket with the given capacity and refill rate.
     *
     * @param capacity    maximum tokens the bucket can hold (also the initial fill)
     * @param refillRate  tokens added per {@code refillPeriod}
     * @param refillPeriod time window for adding {@code refillRate} tokens (e.g. 1 second)
     */
    public TokenBucketRateLimiter(long capacity, long refillRate, Duration refillPeriod) {
        if (capacity <= 0)    throw new IllegalArgumentException("capacity must be > 0");
        if (refillRate <= 0)  throw new IllegalArgumentException("refillRate must be > 0");

        this.capacity       = capacity;
        this.tokens         = capacity;       // start full
        this.tokensPerNano  = (double) refillRate / refillPeriod.toNanos();
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Convenience constructor: {@code refillRate} tokens per second.
     *
     * @param capacity   bucket size
     * @param rps        tokens allowed per second
     */
    public TokenBucketRateLimiter(long capacity, long rps) {
        this(capacity, rps, Duration.ofSeconds(1));
    }

    // ── RateLimiter ───────────────────────────────────────────────────────────

    /**
     * Attempts to consume one token.
     *
     * <p>Thread-safe. Returns {@code true} if the request is allowed.
     * Refills the bucket based on elapsed time before checking.
     */
    @Override
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long available() {
        lock.lock();
        try {
            refill();
            return (long) tokens;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long capacity() {
        return capacity;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Lazy refill — called before every acquire/query while holding the lock. */
    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        double added = elapsed * tokensPerNano;
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }
}
