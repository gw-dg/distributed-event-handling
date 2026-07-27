package com.taskqueue.queue;

import com.taskqueue.model.Task;
import com.taskqueue.repo.TaskRepository;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Objects;

/**
 * Durable {@link TaskQueue} backed by PostgreSQL.
 *
 * <p>Replaces {@link InMemoryTaskQueue} in the production profile.
 * Tasks survive process restarts because they are persisted in the {@code tasks} table
 * before being returned to callers.
 *
 * <h2>Key design from task-queues.md</h2>
 * <ul>
 *   <li><b>Leasing:</b> {@code dequeue()} does not delete rows. It uses
 *       {@code SELECT ... FOR UPDATE SKIP LOCKED} to mark tasks as RUNNING
 *       atomically. If the worker crashes before acking, the lease expires and
 *       the task is reclaimed by {@code StuckTaskReaper}.
 *   <li><b>Batch polling:</b> each call to the repository polls a configurable
 *       batch of rows in one round trip and buffers them per-thread. This amortises
 *       the overhead of a transactional poll across multiple task executions.
 *   <li><b>enqueue:</b> persists the task via {@code TaskRepository.save()} so
 *       tasks are durable before the caller's {@code POST /tasks} returns.
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Multiple workers call {@code dequeue()} concurrently. Each call goes to the DB
 * with {@code SKIP LOCKED}, so the database serialises concurrent claims without
 * application-level locking. The per-thread buffer means each worker drains its
 * own batch without sharing state.
 */
public final class PostgresTaskQueue implements TaskQueue {

    private final TaskRepository repository;
    private final int pollBatchSize;

    /**
     * Per-thread buffer. Each worker thread gets its own queue of tasks fetched
     * in the last batch poll. We drain this before hitting the DB again.
     */
    private final ThreadLocal<Queue<Task>> localBuffer =
            ThreadLocal.withInitial(LinkedList::new);

    public PostgresTaskQueue(TaskRepository repository, int pollBatchSize) {
        this.repository    = Objects.requireNonNull(repository, "repository");
        this.pollBatchSize = pollBatchSize > 0 ? pollBatchSize : 25;
    }

    @Override
    public void enqueue(Task task) {
        Objects.requireNonNull(task, "task");
        repository.save(task);
    }

    /**
     * Leases the next available task from PostgreSQL.
     *
     * <p>First drains the per-thread buffer from the previous poll batch.
     * When empty, polls another batch from the DB. If no tasks are available,
     * sleeps briefly and retries to avoid busy-spinning.
     *
     * <p>This call is blocking — a worker thread parks here until a task is available.
     *
     * @return the next task in RUNNING state
     * @throws InterruptedException if the worker is interrupted during sleep
     */
    @Override
    public Task dequeue() throws InterruptedException {
        Queue<Task> buffer = localBuffer.get();

        while (true) {
            // 1. Drain the local buffer first
            Task buffered = buffer.poll();
            if (buffered != null) {
                return buffered;
            }

            // 2. Buffer empty — hit the DB for a new batch
            List<Task> batch = repository.pollDue(pollBatchSize);

            if (!batch.isEmpty()) {
                // Buffer all but the first; return the first immediately
                for (int i = 1; i < batch.size(); i++) {
                    buffer.add(batch.get(i));
                }
                return batch.get(0);
            }

            // 3. No tasks due yet — sleep briefly to avoid busy-spin
            // From task-queues.md: poll-interval controls how quickly new tasks
            // are picked up after scheduledAt elapses.
            Thread.sleep(500);
        }
    }

    /**
     * Returns the count of runnable tasks (PENDING + RETRYING + SCHEDULED).
     *
     * <p>This is an approximation — the count can change between the DB read
     * and the caller using it. Used for monitoring, not for correctness decisions.
     */
    @Override
    public int size() {
        // Simple implementation — can be optimised with a COUNT query if needed
        return repository.pollDue(0).size();
    }
}
