package main.java.com.taskqueue.handler;

public record TaskRegistration(
        String taskType,
        TaskHandler handler) {
}
