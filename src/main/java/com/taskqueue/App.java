package com.taskqueue;

import java.util.List;

import com.taskqueue.common.Result;
import com.taskqueue.dlq.DeadLetterQueue;
import com.taskqueue.handler.HandlerRegistry;
import com.taskqueue.handler.TaskRegistration;
import com.taskqueue.model.Task;
import com.taskqueue.queue.InMemoryTaskQueue;
import com.taskqueue.queue.TaskQueue;
import com.taskqueue.repo.TaskRepository;
import com.taskqueue.retry.ExponentialBackoffRetryPolicy;
import com.taskqueue.retry.RetryHandler;
import com.taskqueue.retry.RetryPolicy;
import com.taskqueue.worker.Worker;
import com.taskqueue.worker.WorkerPool;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Phase 1 manual-wiring entry point — kept for reference.
 *
 * <p>In Phase 2 the application starts via {@link TaskQueueApplication} (Spring Boot).
 * This class demonstrates how the same components would be wired without Spring —
 * useful for understanding the dependency graph and for running Phase 1 demos.
 *
 * <p>Do NOT use this class in production. Use {@link TaskQueueApplication} instead.
 */
public final class App {

    public static void main(String[] args) throws Exception {

        TaskQueue queue = new InMemoryTaskQueue(100);

        HandlerRegistry registry = new HandlerRegistry(
                List.of(
                        new TaskRegistration(
                                "EMAIL",
                                task -> {
                                    System.out.println("Sending email: " + task.payload());
                                    Thread.sleep(200);
                                    return Result.ok(null);
                                }),

                        new TaskRegistration(
                                "FAIL",
                                task -> Result.fail("Simulated permanent failure")),

                        new TaskRegistration(
                                "RETRY",
                                task -> Result.retryable("Temporary downstream outage"))));

        // Minimal no-op stubs for Phase 1 compatibility (no DB/Spring context)
        TaskRepository noopRepo = noopRepository();
        DeadLetterQueue loggingDlq = (task, reason) ->
                System.err.println("[DLQ] DEAD " + task.id() + ": " + reason);
        RetryPolicy retryPolicy = new ExponentialBackoffRetryPolicy(
                Duration.ofMillis(100), Duration.ofSeconds(5), 3, false);
        RetryHandler retryHandler = new RetryHandler(retryPolicy, noopRepo, loggingDlq);
        ApplicationEventPublisher noopPublisher = event -> {};

        // Phase 2 WorkerPool uses Supplier<Worker> and ThreadPoolTaskExecutor
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("phase1-worker-");
        executor.initialize();

        // Create a no-op circuit breaker decorator for the Phase 1 demo path
        io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry cbRegistry =
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults();
        com.taskqueue.worker.CircuitBreakerHandlerDecorator cbDecorator =
                new com.taskqueue.worker.CircuitBreakerHandlerDecorator(cbRegistry);

        Supplier<Worker> workerFactory = () -> new Worker(
                queue, registry, retryHandler, noopRepo, noopPublisher, cbDecorator);

        WorkerPool pool = new WorkerPool(4, workerFactory, executor);
        pool.start();

        for (int i = 0; i < 10; i++) {
            Task task = Task.create(
                    java.util.UUID.randomUUID().toString(),
                    "EMAIL",
                    """
                    {"to":"user%d@example.com"}
                    """.formatted(i),
                    3, 0);
            queue.enqueue(task);
        }

        Thread.sleep(3000);
        pool.shutdown();

        System.out.println();
        System.out.println("Processed = " + pool.processedCount());
        System.out.println("Failed    = " + pool.failedCount());
        System.out.println("Remaining = " + queue.size());
    }

    /**
     * A no-op TaskRepository for Phase 1 compatibility — doesn't persist anything.
     * Required because Phase 2 Worker and RetryHandler need a real repository.
     */
    private static TaskRepository noopRepository() {
        return new TaskRepository() {
            @Override public void save(Task task) {}
            @Override public boolean saveWithIdempotencyKey(Task t, String key) { return true; }
            @Override public Optional<Task> findById(String id) { return Optional.empty(); }
            @Override public Optional<Task> findByIdempotencyKey(String key) { return Optional.empty(); }
            @Override public List<Task> pollDue(int limit) { return List.of(); }
            @Override public void updateStatus(String id, com.taskqueue.model.TaskStatus s) {}
            @Override public void deleteAll() {}
            @Override public int reclaimStuckTasks(long seconds) { return 0; }
            @Override public int pendingCount() { return 0; }
            @Override public List<Task> findRecent(int limit) { return List.of(); }
            @Override public List<Task> findByStatus(String status, int limit) { return List.of(); }
        };
    }
}
