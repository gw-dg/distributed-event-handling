package com.taskqueue.api;

import java.util.Objects;
import java.util.UUID;

import com.taskqueue.model.Task;
import com.taskqueue.queue.TaskQueue;

/**
 * Entry point for submitting tasks into the queue.
 *
 * Responsible only for:
 * - validating input
 * - creating a Task
 * - enqueueing it
 *
 * Processing is handled by WorkerPool.
 */
public final class TaskSubmissionService {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_PRIORITY = 0;

    private final TaskQueue queue;

    public TaskSubmissionService(TaskQueue queue) {
        this.queue = Objects.requireNonNull(queue);
    }

    public Task submit(String type, String payload) {

        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Task type must not be blank.");
        }

        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null.");
        }

        Task task = Task.create(
                UUID.randomUUID().toString(),
                type,
                payload,
                DEFAULT_MAX_ATTEMPTS,
                DEFAULT_PRIORITY);

        queue.enqueue(task);

        return task;
    }
}
