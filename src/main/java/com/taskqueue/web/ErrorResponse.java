package com.taskqueue.web;

import java.time.Instant;
import java.util.List;

/**
 * Stable error response shape for all API errors.
 *
 * <p>From ch05: "Define one error shape. Handle exceptions centrally."
 * Every error — validation failure, 404, 429, 500 — returns this same shape
 * so clients can reliably parse errors without inspecting HTTP status alone.
 *
 * <p>From production considerations: "Keep error codes stable because clients
 * may depend on them."
 *
 * <p>Example JSON for a validation error:
 * <pre>
 * {
 *   "code": "validation_failed",
 *   "message": "Request validation failed",
 *   "fields": [
 *     {"field": "type", "message": "type must not be blank"}
 *   ],
 *   "timestamp": "2026-01-01T00:00:00Z",
 *   "path": "/tasks"
 * }
 * </pre>
 */
public record ErrorResponse(
        String code,
        String message,
        List<FieldError> fields,
        Instant timestamp,
        String path) {

    /** Per-field validation error detail. */
    public record FieldError(
            String field,
            String message) {
    }

    /** Convenience constructor for errors without field-level details. */
    public static ErrorResponse of(String code, String message, String path) {
        return new ErrorResponse(code, message, List.of(), Instant.now(), path);
    }
}
