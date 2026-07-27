package com.taskqueue.service;

/**
 * Thrown when a task is looked up by id but does not exist.
 *
 * <p>Caught by {@code ApiExceptionHandler} and mapped to HTTP 404 Not Found.
 * Using a domain exception keeps the controller thin — it never constructs
 * a 404 response directly.
 */
public class TaskNotFoundException extends RuntimeException {

    private final String taskId;

    public TaskNotFoundException(String taskId) {
        super("Task not found: " + taskId);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
