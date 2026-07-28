package com.taskqueue.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.taskqueue.port.LeaderElector;

/**
 * Redis-backed leader election using {@code SET key value NX PX ttl}.
 *
 * <p>From leader-election.md: "Leader election with Redis: set a key with NX (only
 * if not exists) and PX (millisecond TTL). The node that succeeds is the leader.
 * All nodes race on heartbeat; only the current leader can renew its own key via
 * Lua CAS."
 *
 * <p>Design:
 * <ul>
 *   <li>Each role has its own Redis key: {@code leader:{role}}.</li>
 *   <li>The value is this node's unique ID ({@code WORKER_ID} env var).</li>
 *   <li>A background {@link ScheduledExecutorService} renews held locks every
 *       {@code heartbeatMs}. Only the owning node can renew (Lua CAS).</li>
 *   <li>{@link #isLeader(String)} is a <em>local cache check</em> — no Redis round-trip
 *       on the hot path.</li>
 * </ul>
 *
 * <p>Failure modes:
 * <ul>
 *   <li>Leader JVM dies → TTL expires → another node acquires within {@code ttlMs}.</li>
 *   <li>Redis is down → all nodes see isLeader=false → no relay runs (safe, not lively).</li>
 * </ul>
 */
public class RedisLeaderElector implements LeaderElector, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisLeaderElector.class);

    // Lua script: only renew if the current value equals nodeId
    // Returns 1 if renewed, 0 if someone else holds the lock
    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('pexpire', KEYS[1], ARGV[2]) " +
            "else " +
            "  return 0 " +
            "end";

    // Lua script: only delete if the current value equals nodeId (safe release)
    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private final StringRedisTemplate redis;
    private final String nodeId;
    private final long ttlMs;
    private final long heartbeatMs;
    private final ScheduledExecutorService heartbeatExecutor;

    /** Local cache: role → true if this node currently holds leadership. */
    private final ConcurrentHashMap<String, Boolean> leaderCache = new ConcurrentHashMap<>();

    public RedisLeaderElector(
            StringRedisTemplate redis,
            String nodeId,
            long ttlMs,
            long heartbeatMs) {
        this.redis    = redis;
        this.nodeId   = nodeId;
        this.ttlMs    = ttlMs;
        this.heartbeatMs = heartbeatMs;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> { Thread t = new Thread(r, "leader-heartbeat"); t.setDaemon(true); return t; });
    }

    /**
     * Starts the heartbeat loop for the given role.
     * Call once per role at application startup.
     */
    public void startHeartbeat(String role) {
        heartbeatExecutor.scheduleAtFixedRate(
                () -> renewOrAcquire(role),
                0, heartbeatMs, TimeUnit.MILLISECONDS);
        log.info("[Leader] Heartbeat started for role '{}' (nodeId={})", role, nodeId);
    }

    @Override
    public boolean isLeader(String role) {
        return Boolean.TRUE.equals(leaderCache.get(role));
    }

    @Override
    public boolean tryAcquire(String role) {
        Boolean ok = redis.opsForValue()
                .setIfAbsent(redisKey(role), nodeId,
                        java.time.Duration.ofMillis(ttlMs));
        boolean acquired = Boolean.TRUE.equals(ok);
        if (acquired) {
            log.info("[Leader] Acquired '{}' (nodeId={})", role, nodeId);
        }
        leaderCache.put(role, acquired);
        return acquired;
    }

    @Override
    public void release(String role) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);
        redis.execute(script,
                java.util.List.of(redisKey(role)),
                nodeId);
        leaderCache.put(role, false);
        log.info("[Leader] Released '{}' (nodeId={})", role, nodeId);
    }

    @Override
    public void close() {
        heartbeatExecutor.shutdownNow();
    }

    // ── private ────────────────────────────────────────────────────────────────

    /**
     * Attempts to renew an existing lock via Lua CAS.
     * Falls back to acquire if this node doesn't hold it.
     */
    private void renewOrAcquire(String role) {
        try {
            if (Boolean.TRUE.equals(leaderCache.get(role))) {
                // Try to renew (extend TTL)
                DefaultRedisScript<Long> script = new DefaultRedisScript<>(RENEW_SCRIPT, Long.class);
                Long result = redis.execute(script,
                        java.util.List.of(redisKey(role)),
                        nodeId, String.valueOf(ttlMs));
                boolean renewed = Long.valueOf(1L).equals(result);
                leaderCache.put(role, renewed);
                if (!renewed) {
                    log.warn("[Leader] Lost leadership for '{}' (another node took over)", role);
                }
            } else {
                // Try to acquire
                tryAcquire(role);
            }
        } catch (Exception e) {
            log.error("[Leader] Heartbeat error for role '{}': {}", role, e.getMessage());
            leaderCache.put(role, false);   // conservative: assume lost
        }
    }

    private static String redisKey(String role) {
        return "leader:" + role;
    }
}
