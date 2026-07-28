package com.taskqueue.worker;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Manages a fixed pool of {@link Worker} threads.
 *
 * <p>Phase 2 changes from Phase 1:
 * <ul>
 *   <li>Uses a Spring-managed {@link ThreadPoolTaskExecutor} instead of a
 *       raw {@code ThreadPoolExecutor}. This gives named threads, graceful
 *       shutdown timeout, and Spring lifecycle integration.
 *   <li>Workers are created via a {@code Supplier<Worker>} so they can be
 *       Spring prototype beans or plain factories.
 *   <li>The pool itself is NOT a {@code @Component}. It is started and stopped
 *       by {@link WorkerPoolLifecycle} after the Spring context is ready.
 * </ul>
 *
 * <p>From ch01: "Do not start background threads in constructors. Use
 * {@code SmartLifecycle}, {@code ApplicationRunner}, or managed executors."
 * This class only starts when explicitly told to by {@code WorkerPoolLifecycle}.
 */
public final class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final ThreadPoolTaskExecutor executor;
    private final Worker[] workers;
    private volatile boolean started;

    /**
     * @param workerCount   number of parallel workers; must be >= 1
     * @param workerFactory creates one Worker instance per thread
     * @param executor      Spring-managed thread pool (named, bounded, graceful shutdown)
     */
    public WorkerPool(
            int workerCount,
            Supplier<Worker> workerFactory,
            ThreadPoolTaskExecutor executor) {

        if (workerCount < 0) {
            throw new IllegalArgumentException("workerCount must be >= 0");
        }
        Objects.requireNonNull(workerFactory, "workerFactory");
        this.executor = Objects.requireNonNull(executor, "executor");

        this.workers = new Worker[workerCount];
        for (int i = 0; i < workerCount; i++) {
            workers[i] = workerFactory.get();
        }
    }

    /**
     * Submits all workers to the executor. Called by {@link WorkerPoolLifecycle#start()}.
     */
    public synchronized void start() {
        if (started) {
            throw new IllegalStateException("WorkerPool already started");
        }
        for (Worker worker : workers) {
            executor.execute(worker);
        }
        started = true;
        log.info("[WorkerPool] Started {} workers", workers.length);
    }

    /**
     * Gracefully stops all workers and waits for the executor to terminate.
     * Called by {@link WorkerPoolLifecycle#stop()}.
     */
    public void shutdown() {
        for (Worker worker : workers) {
            worker.stop();
        }
        // executor.shutdown() respects setWaitForTasksToCompleteOnShutdown
        executor.shutdown();
        log.info("[WorkerPool] Shut down. Processed={} Failed={}",
                processedCount(), failedCount());
    }

    public long processedCount() {
        return Arrays.stream(workers).mapToLong(Worker::processed).sum();
    }

    public long failedCount() {
        return Arrays.stream(workers).mapToLong(Worker::failed).sum();
    }

    public boolean isRunning() {
        return started && !executor.getThreadPoolExecutor().isShutdown();
    }

    public int size() {
        return workers.length;
    }
}
