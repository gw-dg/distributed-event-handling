package com.taskqueue.port;

import java.time.Duration;
import java.util.Optional;

import com.taskqueue.model.Task;
import com.taskqueue.queue.TaskQueue;

/**
 * Extended queue interface required at the broker boundary (Redis Streams, Kafka).
 *
 * <p>From task-queues.md + message-queues.md: "At the broker boundary, reading
 * a message and processing it are two separate steps. The read gives you a
 * timed lease. ACK on success, NACK on failure, reclaim on dead consumer."
 *
 * <p>Extends {@link TaskQueue} so in-memory and Postgres adapters (which use
 * blocking dequeue) remain compatible with the existing {@link com.taskqueue.worker.Worker}.
 * The Redis Streams adapter additionally implements this interface.
 */
public interface AckableTaskQueue extends TaskQueue {

    /**
     * Polls for the next available task within the given timeout.
     *
     * <p>Unlike {@link TaskQueue#dequeue()} (which blocks indefinitely),
     * this returns {@link Optional#empty()} if no task is available within
     * the timeout. This allows the worker to check its shutdown flag periodically.
     *
     * @param timeout how long to wait; must not be null
     * @return a lease wrapping the claimed task, or empty if timeout elapsed
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    Optional<Lease> poll(Duration timeout) throws InterruptedException;

    /**
     * Confirms successful processing of the leased task.
     *
     * <p>For Redis Streams: XACK. After ACK the entry is removed from the
     * consumer's Pending Entry List (PEL).
     *
     * @param lease the lease returned by {@link #poll}; never null
     */
    void ack(Lease lease);

    /**
     * Returns the leased task to the queue for re-delivery.
     *
     * <p>For Redis Streams: the task remains in the PEL with an updated idle timer;
     * another worker will reclaim it after the visibility timeout expires.
     * For Postgres: updates the task to RETRYING so it becomes visible again.
     *
     * @param lease  the lease to release
     * @param reason human-readable reason for the nack (logged, not re-persisted)
     */
    void nack(Lease lease, String reason);

    /**
     * Reclaims tasks whose leases have been held longer than {@code visibilityTimeout}.
     *
     * <p>For Redis Streams: XAUTOCLAIM — transfers PEL entries idle longer than the
     * threshold to this consumer so they are re-processed. For Postgres: updates stuck
     * RUNNING rows to RETRYING.
     *
     * <p>Called on a background schedule by {@link com.taskqueue.worker.WorkerPool}
     * every 5 seconds.
     *
     * @param visibilityTimeout tasks idle longer than this are reclaimed
     * @param maxCount          maximum number of entries to reclaim per call
     * @return number of entries reclaimed
     */
    int reclaimStale(Duration visibilityTimeout, int maxCount);

    /**
     * A time-limited claim on a single task.
     *
     * <p>The lease is the unit of work handed to a worker. The worker either
     * ACKs (success) or NACKs (failure) the lease, never losing track of the task.
     *
     * @param entryId  broker-specific delivery ID (e.g., Redis stream entry ID)
     * @param task     the task payload
     */
    record Lease(String entryId, Task task) {}
}
