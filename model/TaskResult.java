package main.java.com.taskqueue.model;

public record TaskResult(boolean success, String message, boolean retryable) {
    public static TaskResult success() {
        return new TaskResult(true, "Success", false);
    }

    public static TaskResult retry(String reason) {
        return new TaskResult(false, reason, true);
    }

    public static TaskResult failure(String reason) {
        return new TaskResult(false, reason, false);
    }
}
