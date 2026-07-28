package com.taskqueue.worker;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import com.taskqueue.common.Result;
import com.taskqueue.events.TaskFailedEvent;
import com.taskqueue.events.TaskSucceededEvent;
import com.taskqueue.handler.HandlerRegistry;
import com.taskqueue.handler.TaskHandler;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;
import com.taskqueue.queue.TaskQueue;
import com.taskqueue.repo.TaskRepository;
import com.taskqueue.retry.RetryHandler;

/**
 * Worker: drains the TaskQueue and executes tasks via registered handlers.
 *
 * <p>Phase 3 upgrades from Phase 2:
 * <ul>
 *   <li>Handler invocations are wrapped by {@link CircuitBreakerHandlerDecorator}.
 *       If a task type's downstream is failing, the breaker opens and the task
 *       is fast-failed (returned as retryable) rather than consuming a thread
 *       waiting for a timeout.
 * </ul>
 *
 * <p>From ch01: "Worker may be a bean, but its logic must remain unit-testable
 * without Spring." — constructor injection, no field injection, no Spring annotations
 * on this class itself (it is created by {@code QueueConfig}).
 *
 * <p>From task-queues.md: "The worker loop must not die because one bad task failed."
 * All handler exceptions are caught and routed to {@link RetryHandler}.
 */
public final class Worker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final TaskQueue queue;
    private final HandlerRegistry registry;
    private final RetryHandler retryHandler;
    private final TaskRepository repository;
    private final ApplicationEventPublisher events;
    private final CircuitBreakerHandlerDecorator cbDecorator;

    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed    = new AtomicLong();

    /** Cooperative shutdown flag — set to false by WorkerPool.shutdown(). */
    private volatile boolean running = true;

    public Worker(
            TaskQueue queue,
            HandlerRegistry registry,
            RetryHandler retryHandler,
            TaskRepository repository,
            ApplicationEventPublisher events,
            CircuitBreakerHandlerDecorator cbDecorator) {
        this.queue        = Objects.requireNonNull(queue,        "queue");
        this.registry     = Objects.requireNonNull(registry,     "registry");
        this.retryHandler = Objects.requireNonNull(retryHandler, "retryHandler");
        this.repository   = Objects.requireNonNull(repository,   "repository");
        this.events       = Objects.requireNonNull(events,       "events");
        this.cbDecorator  = Objects.requireNonNull(cbDecorator,  "cbDecorator");
    }

    @Override
    public void run() {
        Thread self = Thread.currentThread();
        log.debug("[{}] Worker started", self.getName());

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

        log.debug("[{}] Worker stopped", Thread.currentThread().getName());
    }

    private void execute(Task task) {
        if (task.status() != TaskStatus.RUNNING) {
            try {
                task = task.markRunning();
                repository.save(task);
            } catch (Exception e) {
                // Task may already be marked running in DB — continue with running state
            }
        }

        TaskHandler handler = registry.find(task.type()).orElse(null);

        if (handler == null) {
            log.error("[{}] No handler for type '{}' — task {} sent to DLQ",
                    Thread.currentThread().getName(), task.type(), task.id());
            // Treat as permanent failure — no handler will ever appear for this type
            retryHandler.handleFailure(task, Result.fail("No handler registered for type: " + task.type()));
            failed.incrementAndGet();
            return;
        }

        try {
            // Phase 3: invoke handler through circuit breaker decorator
            Result<Void> result = cbDecorator.execute(handler, task);

            switch (result) {
                case Result.Success<Void> __ -> {
                    Task succeeded = task.recordSuccess();
                    repository.save(succeeded);
                    events.publishEvent(new TaskSucceededEvent(task.id(), task.type(), Instant.now()));
                    processed.incrementAndGet();
                    log.debug("[{}] Task {} succeeded", Thread.currentThread().getName(), task.id());
                }
                case Result.Failure<Void> failure -> {
                    events.publishEvent(new TaskFailedEvent(
                            task.id(), task.type(), failure.error(), failure.retryable(), Instant.now()));
                    retryHandler.handleFailure(task, result);
                    failed.incrementAndGet();
                }
            }

        } catch (Exception ex) {
            log.warn("[{}] Task {} threw exception: {}",
                    Thread.currentThread().getName(), task.id(), ex.getMessage());
            events.publishEvent(new TaskFailedEvent(
                    task.id(), task.type(), ex.getMessage(), true, Instant.now()));
            retryHandler.handleException(task, ex);
            failed.incrementAndGet();
        }
    }

    /** Cooperative stop — worker exits after finishing the current task. */
    public void stop() {
        running = false;
    }

    public long processed() { return processed.get(); }
    public long failed()    { return failed.get(); }
    public boolean isRunning() { return running; }
}
