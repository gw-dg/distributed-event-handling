package com.taskqueue.repo;

import java.util.List;
import java.util.Optional;

import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;

/**
 * Outbound port for task persistence.
 *
 * <p>From hexagonal-architecture.md: this is the outbound port that separates
 * the application core from the database adapter. The application code depends
 * on this interface; only {@code JdbcTaskRepository} knows SQL.
 *
 * <p>Nothing in the application except {@code JdbcTaskRepository} may reference
 * JDBC, SQL strings, or PostgreSQL-specific behavior. Swapping the persistence
 * technology in Phase 4 only touches the implementation of this port.
 *
 * <p>Naming note: methods describe <em>what</em> the application needs, not
 * <em>how</em> persistence works.
 */
public interface TaskRepository {

    /**
     * Persists a task (insert or full update).
     *
     * <p>On first call: inserts a new row.
     * On subsequent calls with the same id: updates all mutable fields
     * (status, attempts, scheduled_at, last_error, version).
     *
     * @param task the task to persist; must not be null
     */
    void save(Task task);

    /**
     * Saves a task with an idempotency key.
     *
     * <p>If a task with the same idempotency_key already exists, this method
     * does nothing (ON CONFLICT DO NOTHING). The caller should then call
     * {@link #findByIdempotencyKey(String)} to retrieve the existing task.
     *
     * @param task           the task to persist
     * @param idempotencyKey the client-supplied dedup key; must not be blank
     * @return true if the row was inserted; false if it already existed
     */
    boolean saveWithIdempotencyKey(Task task, String idempotencyKey);

    /**
     * Finds a task by its id.
     *
     * @param id the task UUID string
     * @return the task if found, or empty
     */
    Optional<Task> findById(String id);

    /**
     * Finds an existing task submitted with the given idempotency key.
     *
     * @param idempotencyKey the key to look up
     * @return the task if found, or empty
     */
    Optional<Task> findByIdempotencyKey(String idempotencyKey);

    /**
     * Leases up to {@code limit} due tasks in a single transaction.
     *
     * <p>This is the hot path. It uses {@code SELECT ... FOR UPDATE SKIP LOCKED}
     * so concurrent workers never claim the same row. The returned tasks have
     * status {@code RUNNING}.
     *
     * <p>From task-queues.md: "When a worker takes a task it does not immediately
     * delete it. Instead the worker takes a lease: an exclusive, time-limited claim."
     *
     * @param limit maximum number of tasks to return; must be >= 1
     * @return list of tasks now in RUNNING state (may be empty if none are due)
     */
    List<Task> pollDue(int limit);

    /**
     * Updates only the status (and version) of a single task.
     *
     * <p>Used by the worker after execution completes and by the reaper when
     * reclaiming stuck tasks. Faster than a full {@link #save(Task)} when
     * only the status changes.
     *
     * @param id     the task id
     * @param status the new status
     */
    void updateStatus(String id, TaskStatus status);

    /**
     * Deletes all tasks. Used only in integration tests to reset state between runs.
     * Must not be called from production code.
     */
    void deleteAll();

    /**
     * Reclaims tasks stuck in {@code RUNNING} past the visibility timeout.
     *
     * <p>Called by {@code StuckTaskReaper} on a schedule. Transitions matching
     * rows from {@code RUNNING} to {@code RETRYING} so they become visible to
     * workers again.
     *
     * @param visibilityTimeoutSeconds tasks RUNNING for longer than this are reclaimed
     * @return number of tasks reclaimed
     */
    int reclaimStuckTasks(long visibilityTimeoutSeconds);

    /**
     * Returns the number of tasks currently in {@code PENDING} or {@code RETRYING} state.
     *
     * <p>Used as a live queue-depth gauge by {@link com.taskqueue.metrics.TaskMetrics}.
     * Called by Micrometer on each Prometheus scrape — must be fast (single COUNT query).
     *
     * @return current count of actionable (not-yet-run) tasks
     */
    int pendingCount();

    /**
     * Returns the most recent tasks ordered by {@code created_at DESC}.
     *
     * @param limit max rows to return; must be >= 1
     * @return list of tasks, newest first
     */
    List<Task> findRecent(int limit);

    /**
     * Returns the most recent tasks in the given status, ordered by {@code created_at DESC}.
     *
     * @param status the status string, e.g. "RUNNING"
     * @param limit  max rows
     * @return list of matching tasks, newest first
     */
    List<Task> findByStatus(String status, int limit);
}
