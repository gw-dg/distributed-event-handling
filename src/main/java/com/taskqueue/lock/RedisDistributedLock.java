package com.taskqueue.lock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * General-purpose distributed mutex backed by Redis.
 *
 * <p>From distributed-locks.md: "Use {@code SET key value NX PX ttl} to acquire.
 * Use Lua compare-and-delete to release — never release a lock you don't own.
 * Set a TTL so a crashed holder's lock expires automatically."
 *
 * <p>This class wraps the acquire/release lifecycle in {@link #runIfAcquired},
 * ensuring the lock is always released even if the action throws.
 *
 * <p>From the roadmap: the canonical failure mode this prevents is an expired-and-
 * reacquired lock being deleted by the original holder on return from a long GC pause.
 * The Lua CAS (compare value before delete) prevents this.
 */
public class RedisDistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);

    // Lua: delete only if the value matches (safe release)
    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Acquires the lock for {@code key} with the given TTL, runs {@code action},
     * then releases the lock.
     *
     * <p>If the lock is already held, {@code action} is NOT run and the method
     * returns {@code false} immediately (no retry / no blocking).
     *
     * @param key    Redis key for this mutex; typically namespaced, e.g. "task-lock:abc"
     * @param ttl    how long the lock lives if the holder crashes before releasing
     * @param action the work to do while holding the lock; may throw
     * @return {@code true} if the lock was acquired and the action ran; {@code false} if
     *         the lock was already held
     * @throws Exception if {@code action} throws — the lock is still released
     */
    public boolean runIfAcquired(String key, Duration ttl, ThrowingRunnable action) throws Exception {
        String token = UUID.randomUUID().toString();

        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("[Lock] Could not acquire '{}' — already held", key);
            return false;
        }

        try {
            log.debug("[Lock] Acquired '{}' (token={})", key, token);
            action.run();
            return true;
        } finally {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);
            redis.execute(script, List.of(key), token);
            log.debug("[Lock] Released '{}'", key);
        }
    }

    /**
     * Variant that returns the result of the action.
     *
     * @param <T>    the result type
     * @param key    Redis key for this mutex
     * @param ttl    lock TTL
     * @param action the callable to run while holding the lock
     * @param absent the value to return if the lock could not be acquired
     * @return action result, or {@code absent} if not acquired
     * @throws Exception if {@code action} throws
     */
    public <T> T runIfAcquired(String key, Duration ttl, ThrowingCallable<T> action, T absent)
            throws Exception {
        String token = UUID.randomUUID().toString();

        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            return absent;
        }

        try {
            return action.call();
        } finally {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);
            redis.execute(script, List.of(key), token);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingCallable<T> {
        T call() throws Exception;
    }
}
