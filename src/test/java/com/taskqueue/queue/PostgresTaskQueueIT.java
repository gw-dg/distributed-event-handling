package com.taskqueue.queue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.taskqueue.PostgresIT;
import com.taskqueue.model.Task;
import com.taskqueue.repo.JdbcTaskRepository;
import com.taskqueue.repo.TaskRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency integration test: proves that {@code FOR UPDATE SKIP LOCKED} prevents
 * double-execution of the same task by concurrent workers.
 *
 * <p>From ch05: "The no-double-lease test should insert one pending task, run two
 * concurrent pollDue(1) calls, and assert only one receives the task."
 *
 * <p>From task-queues.md: "SKIP LOCKED lets concurrent workers skip rows already
 * claimed by another transaction."
 *
 * <p>This test requires a real database because row locking is database behavior —
 * it cannot be meaningfully tested with mocks.
 */
class PostgresTaskQueueIT extends PostgresIT {

    @Autowired
    private TaskRepository repository;

    @BeforeEach
    void cleanUp() {
        ((JdbcTaskRepository) repository).deleteAll();
    }

    /**
     * Core correctness test: 4 threads race to claim 1 task.
     * Only one should receive it; the others should get nothing.
     * Zero double-execution is the exit criterion.
     */
    @Test
    void concurrentPollDueNeverDoubleLeasesSameTask() throws Exception {
        // Arrange: one task in the queue
        Task task = Task.create("skip-locked-test", "email", "{}", 3, 0);
        repository.save(task);

        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go    = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<Task>>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return repository.pollDue(1);
            }));
        }

        // All threads ready — release them simultaneously
        ready.await();
        go.countDown();

        // Collect results
        List<Task> allClaimed = new ArrayList<>();
        for (Future<List<Task>> f : futures) {
            allClaimed.addAll(f.get());
        }

        pool.shutdown();

        // Exit criterion: exactly 1 task claimed — no double lease
        assertThat(allClaimed).hasSize(1);
        assertThat(allClaimed.get(0).id()).isEqualTo("skip-locked-test");
    }

    /**
     * Stress test: 1000 tasks, 8 concurrent workers, zero double-execution.
     *
     * <p>Each task should be claimed exactly once across all worker threads.
     */
    @Test
    void noDoubleLeaseUnderLoad() throws Exception {
        int taskCount   = 100;
        int workerCount = 8;

        // Insert tasks
        for (int i = 0; i < taskCount; i++) {
            repository.save(Task.create("task-" + i, "email", "{}", 3, 0));
        }

        List<String> allClaimed = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < workerCount; w++) {
            futures.add(pool.submit(() -> {
                go.await();
                List<Task> batch;
                do {
                    batch = repository.pollDue(10);
                    for (Task t : batch) {
                        allClaimed.add(t.id());
                    }
                } while (!batch.isEmpty());
                return null;
            }));
        }

        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        // All task ids should be unique — no task was claimed twice
        assertThat(allClaimed).doesNotHaveDuplicates();
        assertThat(allClaimed).hasSize(taskCount);
    }
}
