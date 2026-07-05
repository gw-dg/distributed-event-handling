package main.java.com.taskqueue.worker;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import main.java.com.taskqueue.common.Result;
import main.java.com.taskqueue.handler.HandlerRegistry;
import main.java.com.taskqueue.handler.TaskHandler;
import main.java.com.taskqueue.model.Task;
import main.java.com.taskqueue.queue.TaskQueue;

/**
 * A Worker continuously drains a TaskQueue and delegates each Task to the
 * appropriate TaskHandler.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Block waiting for new tasks.</li>
 * <li>Look up the correct handler.</li>
 * <li>Execute the task.</li>
 * <li>Update the immutable Task state.</li>
 * <li>Honor interruption for graceful shutdown.</li>
 * <li>Prevent handler failures from killing the worker thread.</li>
 * </ul>
 */
public final class Worker implements Runnable {

    private final TaskQueue queue;
    private final HandlerRegistry registry;

    /**
     * Number of successfully processed tasks.
     */
    private final AtomicLong processed = new AtomicLong();

    /**
     * Visibility-only flag for cooperative shutdown.
     */
    private volatile boolean running = true;

    public Worker(TaskQueue queue, HandlerRegistry registry) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void run() {

        Thread self = Thread.currentThread();

        while (running && !self.isInterrupted()) {

            final Task task;

            try {
                task = queue.dequeue();
            } catch (InterruptedException e) {
                self.interrupt();
                break;
            }

            execute(task);
        }
    }

    /**
     * Executes a single task while ensuring any handler failure is isolated to
     * the current task.
     */
    private void execute(Task task) {

        TaskHandler handler = registry.find(task.type())
                .orElse(null);

        if (handler == null) {
            System.err.printf(
                    "[%s] No handler registered for task type '%s'%n",
                    Thread.currentThread().getName(),
                    task.type());

            task.recordFailure(
                    "No handler registered for type: " + task.type(),
                    false);

            return;
        }

        try {

            Task runningTask = task.markRunning();

            Result<Void> result = handler.handle(runningTask);

            switch (result) {

                case Result.Success<Void> ignored -> {
                    runningTask.recordSuccess();
                    processed.incrementAndGet();
                }

                case Result.Failure<Void> failure -> {
                    runningTask.recordFailure(
                            failure.error(),
                            failure.retryable());
                }
            }

        } catch (Exception ex) {

            try {
                task.recordFailure(
                        ex.getMessage() == null
                                ? ex.getClass().getSimpleName()
                                : ex.getMessage(),
                        true);
            } catch (Exception ignored) {
                // Never allow task-state failures to terminate the worker.
            }

            System.err.printf(
                    "[%s] Task %s failed: %s%n",
                    Thread.currentThread().getName(),
                    task.id(),
                    ex.getMessage());
        }
    }

    /**
     * Requests a graceful shutdown.
     *
     * The Worker will exit after finishing the current iteration. If blocked in
     * dequeue(), the owning thread should also be interrupted.
     */
    public void stop() {
        running = false;
    }

    public long processed() {
        return processed.get();
    }

    public boolean isRunning() {
        return running;
    }
}