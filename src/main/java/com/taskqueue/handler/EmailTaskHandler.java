package com.taskqueue.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.common.Result;
import com.taskqueue.model.Task;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Concrete handler for {@code type = "email"}.
 *
 * <p>Simulates sending an SMTP email. In production this would delegate to
 * a mail client (e.g., JavaMail, AWS SES SDK). Here it uses a random failure
 * to demonstrate retryable vs permanent failure paths.
 *
 * <p>Demonstrates the Strategy pattern (strategy.md): the Worker doesn't know
 * it's sending email — it only calls {@code handle(task)}.
 *
 * <p>Idempotent: sending the same email twice (same task id replayed) is safe
 * because real SMTP calls should include the task id as a message-id header
 * for dedup by the mail server.
 */
@Component
public final class EmailTaskHandler implements TaskHandler {

    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    public EmailTaskHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedType() {
        return "email";
    }

    @Override
    public Result<Void> handle(Task task) throws Exception {
        // Parse payload to extract target address
        JsonNode payload = objectMapper.readTree(task.payload());
        String to = payload.path("to").asText("unknown@example.com");

        // Simulate processing time (50–150 ms)
        Thread.sleep(50 + random.nextInt(100));

        // Simulate 10% transient failure (e.g., SMTP timeout)
        if (random.nextInt(10) == 0) {
            return Result.retryable("SMTP connection timeout — will retry");
        }

        System.out.printf("[EmailTaskHandler] Sent email to '%s' for task %s%n", to, task.id());
        return Result.ok(null);
    }
}
