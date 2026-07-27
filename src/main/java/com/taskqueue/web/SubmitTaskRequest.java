package com.taskqueue.web;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Inbound DTO for {@code POST /tasks}.
 *
 * <p>From ch05: "Validation belongs at the edge. Domain invariants belong in
 * domain factories or constructors."
 *
 * <p>Bean Validation annotations enforce API-level constraints before the
 * request reaches the service. The controller uses {@code @Valid} to trigger
 * validation; {@code ApiExceptionHandler} maps violations to 400 responses.
 *
 * <p>Deliberate omissions: task id, attempts, createdAt, status — these are
 * set by the service and must not be accepted from untrusted client input.
 * From ch02: "A submit request should only contain fields the client is allowed to set."
 *
 * <p>{@code payload} is {@code JsonNode} so the controller accepts arbitrary
 * JSON objects without needing a fixed schema. The domain stores it as a String.
 */
public record SubmitTaskRequest(

        @NotBlank(message = "type must not be blank")
        @Pattern(
                regexp = "^[a-zA-Z][a-zA-Z0-9_.-]*$",
                message = "type must start with a letter and contain only letters, digits, _, ., -")
        String type,

        @NotNull(message = "payload must not be null")
        JsonNode payload,

        @Min(value = 1,  message = "maxAttempts must be >= 1")
        @Max(value = 20, message = "maxAttempts must be <= 20")
        Integer maxAttempts,

        @Min(value = 0,   message = "priority must be >= 0")
        @Max(value = 100, message = "priority must be <= 100")
        Integer priority,

        /** Optional future timestamp. If provided, task starts as SCHEDULED. */
        Instant scheduledAt,

        /** Optional client-supplied dedup key. Same key + same type = same task returned. */
        String idempotencyKey) {
}
