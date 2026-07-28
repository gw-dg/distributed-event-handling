package com.taskqueue.port;

/**
 * Port for distributed leader election.
 *
 * <p>From leader-election.md + hexagonal-architecture.md: "Any singleton job
 * (outbox relay, scheduled reaper) calls {@link #isLeader(String)} before doing
 * its work. It never knows whether leadership is decided by Redis, ZooKeeper,
 * or a database row."
 *
 * <p>Implementation: {@link com.taskqueue.lock.RedisLeaderElector} — uses
 * Redis {@code SET key value NX PX ttl} with a Lua CAS heartbeat.
 *
 * <p>Roles: each distinct singleton job registers under its own role string
 * (e.g., {@code "outbox-relay"}, {@code "scheduler"}). Multiple roles can
 * be held by different nodes simultaneously.
 */
public interface LeaderElector {

    /**
     * Returns {@code true} if this node currently holds leadership for the given role.
     *
     * <p>This is a <em>local</em> check backed by the last heartbeat result;
     * it does not make a synchronous Redis call on every invocation.
     * The background heartbeat thread updates the cached state every
     * {@code taskqueue.leader.heartbeat-ms} milliseconds.
     *
     * @param role the singleton role name (e.g., "outbox-relay")
     * @return true if this node is currently the leader for the role
     */
    boolean isLeader(String role);

    /**
     * Attempts to acquire leadership for the given role.
     *
     * <p>Uses Redis {@code SET NX PX} — succeeds only if the key does not exist.
     * Called once during startup and by the heartbeat renewer.
     *
     * @param role the singleton role name
     * @return true if leadership was acquired or renewed
     */
    boolean tryAcquire(String role);

    /**
     * Releases leadership for the given role.
     *
     * <p>Uses Lua compare-and-delete: only releases if this node still owns the key,
     * preventing a node from revoking another node's newly-acquired leadership.
     * Called during graceful shutdown.
     *
     * @param role the singleton role name
     */
    void release(String role);
}
