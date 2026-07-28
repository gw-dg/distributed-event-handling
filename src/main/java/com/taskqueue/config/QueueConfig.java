package com.taskqueue.config;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.taskqueue.dlq.DeadLetterQueue;
import com.taskqueue.dlq.PostgresDeadLetterQueue;
import com.taskqueue.handler.HandlerRegistry;
import com.taskqueue.handler.TaskHandler;
import com.taskqueue.queue.InMemoryTaskQueue;
import com.taskqueue.queue.PostgresTaskQueue;
import com.taskqueue.queue.TaskQueue;
import com.taskqueue.ratelimit.RateLimiter;
import com.taskqueue.ratelimit.TokenBucketRateLimiter;
import com.taskqueue.repo.DeadLetterRepository;
import com.taskqueue.repo.TaskRepository;
import com.taskqueue.retry.ExponentialBackoffRetryPolicy;
import com.taskqueue.retry.RetryHandler;
import com.taskqueue.retry.RetryPolicy;
import com.taskqueue.web.RateLimitFilter;
import com.taskqueue.worker.CircuitBreakerHandlerDecorator;
import com.taskqueue.worker.Worker;
import com.taskqueue.worker.WorkerPool;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

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
 * <p>Phase 3 additions:
 * <ul>
 *   <li>{@link DeadLetterQueue} — {@link PostgresDeadLetterQueue} replacing the logging impl
 *   <li>{@link RateLimiter} — {@link TokenBucketRateLimiter} wired from properties
 *   <li>{@link RateLimitFilter} — registered as a Servlet filter for POST /tasks
 *   <li>{@link CircuitBreakerHandlerDecorator} — wraps handler invocations in a breaker
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

    // ── Dead Letter Queue (Phase 3: durable Postgres implementation) ─────────

    /**
     * Phase 3: Persistent DLQ backed by the {@code dead_letter} table.
     *
     * <p>Replaces {@code LoggingDeadLetterQueue} which only printed to stdout.
     * This bean is injected into {@link RetryHandler}.
     */
    @Bean
    @ConditionalOnMissingBean
    public DeadLetterQueue deadLetterQueue(DeadLetterRepository repository) {
        return new PostgresDeadLetterQueue(repository);
    }

    // ── Rate Limiting (Phase 3) ──────────────────────────────────────────────

    /**
     * Token Bucket rate limiter wired from {@code taskqueue.rate-limit.*} properties.
     *
     * <p>From rate-limiting.md: Token Bucket is the dominant algorithm for burst-aware
     * rate limiting. The bucket starts full (capacity), refills at refillRate tokens/second,
     * and each request consumes one token. When empty → 429.
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiter rateLimiter(TaskQueueProperties properties) {
        TaskQueueProperties.RateLimit rl = properties.rateLimit();
        return new TokenBucketRateLimiter(rl.capacity(), rl.refillRate());
    }

    /**
     * Registers the rate limit filter as a Servlet filter for POST /tasks.
     *
     * <p>Using {@link FilterRegistrationBean} so Spring Boot registers it with
     * the correct ordering — before any servlet handling, after security filters.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter rateLimiter) {
        RateLimitFilter filter = new RateLimitFilter(rateLimiter);
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/tasks");   // only POST /tasks
        registration.setOrder(1);               // run early in the filter chain
        registration.setName("rateLimitFilter");
        return registration;
    }

    // ── Circuit Breaker Decorator (Phase 3) ──────────────────────────────────

    /**
     * Wraps handler invocations in per-type Resilience4j circuit breakers.
     *
     * <p>From circuit-breakers.md: "Each task type gets its own breaker so a
     * sick 'email' handler does not trip the 'report' handler."
     */
    @Bean
    public CircuitBreakerHandlerDecorator circuitBreakerHandlerDecorator(
            CircuitBreakerRegistry registry) {
        return new CircuitBreakerHandlerDecorator(registry);
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
        int count = Math.max(1, properties.workers().count());
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
     * <p>Phase 3: Workers now receive the {@link CircuitBreakerHandlerDecorator} which
     * wraps every handler call in a per-type circuit breaker.
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
            CircuitBreakerHandlerDecorator cbDecorator,
            ThreadPoolTaskExecutor workerTaskExecutor) {

        int count = properties.workers().count();

        Supplier<Worker> workerFactory = () -> new Worker(
                taskQueue,
                handlerRegistry,
                retryHandler,
                repository,
                events,
                cbDecorator);

        return new WorkerPool(count, workerFactory, workerTaskExecutor);
    }
}
