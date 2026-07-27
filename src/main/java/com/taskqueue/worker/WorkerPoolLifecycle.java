package com.taskqueue.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Binds the {@link WorkerPool} to the Spring application lifecycle.
 *
 * <p>From ch01: "For the worker pool, this matters. Starting workers in a constructor
 * is a mistake because dependencies may not be fully initialized. Use a lifecycle hook."
 *
 * <p>{@link SmartLifecycle} is preferred over {@code @PostConstruct} for background
 * executors because:
 * <ul>
 *   <li>It starts <em>after</em> the entire application context is ready (all beans
 *       initialized, all data sources connected, Flyway migrations run).
 *   <li>It participates in Spring's ordered shutdown phase, giving workers time to
 *       finish their current task before the JVM exits.
 *   <li>It is restartable — the container can call {@code stop()} then {@code start()}
 *       without recreating beans.
 * </ul>
 *
 * <p>Sequence:
 * <pre>
 *   Spring context ready → WorkerPoolLifecycle.start() → WorkerPool.start() → workers poll queue
 *   SIGTERM received     → WorkerPoolLifecycle.stop()  → WorkerPool.shutdown() → executor drain
 * </pre>
 */
@Component
public final class WorkerPoolLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerPoolLifecycle.class);

    private final WorkerPool workerPool;
    private volatile boolean running;

    public WorkerPoolLifecycle(WorkerPool workerPool) {
        this.workerPool = workerPool;
    }

    @Override
    public void start() {
        workerPool.start();
        running = true;
        log.info("[WorkerPoolLifecycle] Worker pool started");
    }

    @Override
    public void stop() {
        workerPool.shutdown();
        running = false;
        log.info("[WorkerPoolLifecycle] Worker pool stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Phase: higher phase number means this bean starts later and stops earlier.
     * Default is {@link Integer#MAX_VALUE}; we use a lower number so workers
     * start after DB and web are ready, and stop before DB connections close.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 10;
    }
}
