package com.taskqueue.ratelimit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Distributed token bucket rate limiter backed by a Redis Lua script.
 *
 * <p>From rate-limiting.md: "The in-JVM token bucket (Phase 3) works for a single
 * node. In a cluster, each node has its own counter — the aggregate rate is N×limit.
 * Fix: move the bucket state to Redis and use a Lua script to read-refill-decrement
 * atomically in one round trip."
 *
 * <p>The Lua script is atomic from Redis's perspective (single-threaded eval):
 * <ol>
 *   <li>Read current tokens and last-refill timestamp.</li>
 *   <li>Calculate elapsed seconds since last refill.</li>
 *   <li>Add {@code elapsed × refillRate} tokens (capped at {@code capacity}).</li>
 *   <li>If tokens >= 1, decrement by 1 and return 1 (allowed).</li>
 *   <li>Otherwise return 0 (denied).</li>
 * </ol>
 *
 * <p>Implements the same {@link RateLimiter} port as {@link TokenBucketRateLimiter}
 * so the HTTP filter and worker code are unchanged.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    /**
     * Lua script: atomic token bucket.
     * KEYS[1] = bucket key
     * ARGV[1] = capacity
     * ARGV[2] = refill rate (tokens/second)
     * Returns: {allowed (0|1), remaining_tokens}
     */
    private static final String BUCKET_SCRIPT = """
            local key        = KEYS[1]
            local capacity   = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local now        = tonumber(redis.call('time')[1])
            
            local data       = redis.call('hmget', key, 'tokens', 'last_refill')
            local tokens     = tonumber(data[1]) or capacity
            local lastRefill = tonumber(data[2]) or now
            
            local elapsed    = math.max(0, now - lastRefill)
            tokens           = math.min(capacity, tokens + elapsed * refillRate)
            
            local allowed = 0
            if tokens >= 1 then
                tokens  = tokens - 1
                allowed = 1
            end
            
            redis.call('hmset', key, 'tokens', tokens, 'last_refill', now)
            redis.call('expire', key, math.ceil(capacity / refillRate) + 10)
            
            return { allowed, math.floor(tokens) }
            """;

    private final StringRedisTemplate redis;
    private final String bucketKey;
    private final long capacity;
    private final long refillRate;
    private final DefaultRedisScript<List> script;

    // Local cache of remaining tokens (updated after each Lua call)
    private volatile long cachedTokens;

    public RedisTokenBucketRateLimiter(
            StringRedisTemplate redis,
            String bucketKey,
            long capacity,
            long refillRate) {
        this.redis      = redis;
        this.bucketKey  = bucketKey;
        this.capacity   = capacity;
        this.refillRate = refillRate;
        this.cachedTokens = capacity;
        this.script = new DefaultRedisScript<>(BUCKET_SCRIPT, List.class);
    }

    @Override
    public boolean tryAcquire() {
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(script,
                    List.of(bucketKey),
                    String.valueOf(capacity),
                    String.valueOf(refillRate));
            if (result == null || result.size() < 2) {
                log.warn("[RateLimiter] Unexpected Lua response, allowing request");
                return true;   // fail open
            }
            long allowed  = result.get(0);
            cachedTokens  = result.get(1);
            return allowed == 1L;
        } catch (Exception e) {
            log.error("[RateLimiter] Redis error, failing open: {}", e.getMessage());
            return true;   // fail open — don't block requests if Redis is down
        }
    }

    @Override
    public long available() {
        return cachedTokens;
    }

    @Override
    public long capacity() {
        return capacity;
    }
}
