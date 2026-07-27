package com.taskqueue.config;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.taskqueue.handler.HandlerRegistry;
import com.taskqueue.handler.TaskHandler;
import com.taskqueue.queue.InMemoryTaskQueue;
import com.taskqueue.queue.PostgresTaskQueue;
import com.taskqueue.queue.TaskQueue;
import com.taskqueue.repo.TaskRepository;
import com.taskqueue.retry.ExponentialBackoffRetryPolicy;
import com.taskqueue.retry.RetryHandler;
import com.taskqueue.retry.RetryPolicy;
import com.taskqueue.worker.Worker;
import com.taskqueue.worker.WorkerPool;

/**
 * Central Spring configuration for the Task Queue infrastructure.
 *
 * <p>From ch01: "Manual DI from Phase 1 becomes Spring DI here."
 * This class is the composition root — it makes infrastructure decisions:
 * which queue implementation, which retry policy, how many workers.
 * Application classes (Worker, RetryHandler, services) remain unaware of
 * these choices.
 *
 * <p>From ch03: "Use {@code @Bean} when construction needs decisions:
 * choosing an implementation behind an interface, supplying constructor
 * arguments from properties, centralising infrastructure configuration."
 *
 * <p>Key beans declared here:
 * <ul>
 *   <li>{@link TaskQueue} — conditional: InMemory or Postgres
 *   <li>{@link HandlerRegistry} — auto-discovers all @Component TaskHandlers
 *   <li>{@link RetryPolicy} — wired from properties
 *   <li>{@link WorkerPool} — wired from properties + executor
 *   <li>{@link Clock} — injectable for deterministic tests
 * </ul>
 */
@Configuration
public class QueueConfig {

    // ── Clock ───────────────────────────────────────────────────────────────

    /**
     * Injectable Clock for deterministic tests.
     *
     * <p>Services call {@code Instant.now(clock)} instead of {@code Instant.now()}.
     * Tests inject {@code Clock.fixed(...)} to freeze time.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    // ── Task Queue — conditional on taskqueue.queue.type ────────────────────

    /**
     * In-memory queue — active when {@code taskqueue.queue.type=memory}.
     * Used for local development and unit tests. No Postgres required.
     */
    @Bean
    @ConditionalOnProperty(name = "taskqueue.queue.type", havingValue = "memory")
    public TaskQueue inMemoryTaskQueue(TaskQueueProperties properties) {
        return new InMemoryTaskQueue(properties.queue().capacity());
    }

    /**
     * Postgres-backed queue — active when {@code taskqueue.queue.type=postgres}.
     * Used in production. Requires a running DataSource and Flyway migrations.
     */
    @Bean
    @ConditionalOnProperty(name = "taskqueue.queue.type", havingValue = "postgres",
            matchIfMissing = true)
    public TaskQueue postgresTaskQueue(
            TaskRepository repository,
            TaskQueueProperties properties) {
        return new PostgresTaskQueue(repository, properties.queue().pollBatchSize());
    }

    // ── Handler Registry ────────────────────────────────────────────────────

    /**
     * Auto-discovers all {@code TaskHandler} beans via Spring injection.
     *
     * <p>Every {@code @Component} implementing {@link TaskHandler} and providing
     * a non-blank {@link TaskHandler#supportedType()} is included. No manual
     * registry entry is needed when adding a new handler type.
     *
     * @param handlers Spring injects the list of all TaskHandler beans
     */
    @Bean
    public HandlerRegistry handlerRegistry(List<TaskHandler> handlers) {
        return new HandlerRegistry(handlers);
    }

    // ── Retry Policy ────────────────────────────────────────────────────────

    /**
     * Exponential backoff with jitter, wired from {@code taskqueue.retry.*} properties.
     *
     * <p>From retries.md: "Exponential backoff with full jitter spreads retries
     * randomly. When hundreds of tasks fail simultaneously, jitter ensures they
     * don't all retry at the same millisecond."
     */
    @Bean
    public RetryPolicy retryPolicy(TaskQueueProperties properties) {
        TaskQueueProperties.Retry retry = properties.retry();
        return new ExponentialBackoffRetryPolicy(
                retry.baseDelay(),
                retry.maxDelay(),
                retry.maxAttempts(),
                retry.jitter());
    }

    // ── Worker Executor ─────────────────────────────────────────────────────

    /**
     * Named thread pool executor for worker threads.
     *
     * <p>From ch06: "Configure a named executor — name every executor and monitor
     * queue depth, active threads, and rejected tasks."
     *
     * <p>queueCapacity=0 means tasks are submitted to the pool directly. If the
     * pool is full, the CallerRunsPolicy (inherited) or rejection handler kicks in.
     */
    @Bean(name = "workerTaskExecutor")
    public ThreadPoolTaskExecutor workerTaskExecutor(TaskQueueProperties properties) {
        int count = properties.workers().count();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(count);
        executor.setMaxPoolSize(count);
        executor.setQueueCapacity(0);        // unbuffered — workers run immediately or reject
        executor.setThreadNamePrefix("task-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(
                (int) properties.workers().shutdownTimeout().toSeconds());
        executor.initialize();
        return executor;
    }

    // ── Worker Pool ─────────────────────────────────────────────────────────

    /**
     * Creates the WorkerPool with a factory function that instantiates one Worker per thread.
     *
     * <p>The Supplier creates a new Worker for each call — all workers share the same
     * queue, registry, retryHandler, and repository beans (all singletons).
     *
     * <p>WorkerPool is NOT a {@code @Component} — it is started/stopped by
     * {@link com.taskqueue.worker.WorkerPoolLifecycle}.
     */
    @Bean
    public WorkerPool workerPool(
            TaskQueueProperties properties,
            TaskQueue taskQueue,
            HandlerRegistry handlerRegistry,
            RetryHandler retryHandler,
            TaskRepository repository,
            ApplicationEventPublisher events,
            ThreadPoolTaskExecutor workerTaskExecutor) {

        int count = properties.workers().count();

        Supplier<Worker> workerFactory = () -> new Worker(
                taskQueue,
                handlerRegistry,
                retryHandler,
                repository,
                events);

        return new WorkerPool(count, workerFactory, workerTaskExecutor);
    }
}
