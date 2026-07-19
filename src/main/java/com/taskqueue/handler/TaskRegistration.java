package com.taskqueue.handler;

public record TaskRegistration(
        String taskType,
        TaskHandler handler) {
}
