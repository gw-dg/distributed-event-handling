package main.java.com.taskqueue.worker;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import main.java.com.taskqueue.handler.HandlerRegistry;
import main.java.com.taskqueue.queue.TaskQueue;

public final class WorkerPool {

    private static final int DEFAULT_QUEUE_CAPACITY = 16;
    private static final long KEEP_ALIVE_SECONDS = 60L;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;

    private final ThreadPoolExecutor executor;
    private final Worker[] workers;

    private volatile boolean started;

    public WorkerPool(
            int workerCount,
            TaskQueue queue,
            HandlerRegistry registry) {

        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(registry, "registry");

        if (workerCount < 1) {
            throw new IllegalArgumentException(
                    "workerCount must be >= 1");
        }

        this.workers = new Worker[workerCount];

        for (int i = 0; i < workerCount; i++) {
            workers[i] = new Worker(queue, registry);
        }

        this.executor = new ThreadPoolExecutor(
                workerCount, // core threads
                workerCount, // max threads
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY),
                threadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());

        executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Starts every Worker exactly once.
     */
    public synchronized void start() {

        if (started) {
            throw new IllegalStateException(
                    "WorkerPool already started");
        }

        for (Worker worker : workers) {
            executor.execute(worker);
        }

        started = true;
    }

    /**
     * Gracefully shuts down the pool.
     */
    public void shutdown() {

        // Ask every worker to stop.
        for (Worker worker : workers) {
            worker.stop();
        }

        /*
         * Interrupt workers blocked in queue.dequeue().
         */
        executor.shutdownNow();

        try {

            if (!executor.awaitTermination(
                    SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS)) {

                throw new IllegalStateException(
                        "Timed out waiting for WorkerPool shutdown");
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Interrupted while shutting down WorkerPool",
                    e);
        }
    }

    public boolean isRunning() {
        return started && !executor.isShutdown();
    }

    public int size() {
        return workers.length;
    }

    public boolean isTerminated() {
        return executor.isTerminated();
    }

    /**
     * Exposes the underlying executor for monitoring if needed.
     */
    public ThreadPoolExecutor executor() {
        return executor;
    }

    private static ThreadFactory threadFactory() {

        AtomicInteger counter = new AtomicInteger(1);

        return runnable -> {

            Thread thread = new Thread(
                    runnable,
                    "worker-" + counter.getAndIncrement());

            thread.setDaemon(false);

            thread.setUncaughtExceptionHandler(
                    (t, ex) -> System.err.printf(
                            "[%s] Uncaught exception: %s%n",
                            t.getName(),
                            ex.getMessage()));

            return thread;
        };
    }
}