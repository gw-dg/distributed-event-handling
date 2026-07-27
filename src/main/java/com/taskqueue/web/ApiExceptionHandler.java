package com.taskqueue.web;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.taskqueue.service.TaskNotFoundException;

/**
 * Global exception handler: maps all domain and validation exceptions to
 * consistent HTTP error responses.
 *
 * <p>From ch05: "Define one error shape. Handle exceptions centrally.
 * Controllers stay focused on the happy path. Error translation is one policy."
 *
 * <p>Every exception that escapes a controller flows here before being
 * serialised to JSON. This ensures:
 * <ul>
 *   <li>All errors have the same {@link ErrorResponse} JSON shape.
 *   <li>Stack traces never reach the client (from production considerations).
 *   <li>Error codes are stable — clients can depend on them.
 * </ul>
 *
 * <p>From ch05 production: "Log validation failures at low severity (client error).
 * Log unexpected exceptions with full context."
 */
@RestControllerAdvice
public final class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Bean Validation failures → 400 Bad Request.
     *
     * <p>Thrown by Spring MVC when {@code @Valid} fails on a {@code @RequestBody}.
     * We extract field-level messages and include them in the response.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        List<ErrorResponse.FieldError> fields = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();

        log.debug("Validation failed for {}: {}", request.getRequestURI(), fields);

        return ResponseEntity.badRequest().body(new ErrorResponse(
                "validation_failed",
                "Request validation failed",
                fields,
                Instant.now(),
                request.getRequestURI()));
    }

    /**
     * Unknown task id → 404 Not Found.
     */
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            TaskNotFoundException ex,
            HttpServletRequest request) {

        log.debug("Task not found: {}", ex.getTaskId());

        return ResponseEntity.status(404).body(ErrorResponse.of(
                "task_not_found",
                ex.getMessage(),
                request.getRequestURI()));
    }

    /**
     * All other unexpected exceptions → 500 Internal Server Error.
     *
     * <p>The response body is intentionally vague — stack traces must not reach clients.
     * Full exception details are logged server-side for debugging.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unexpected error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);

        return ResponseEntity.internalServerError().body(ErrorResponse.of(
                "internal_error",
                "An unexpected error occurred",
                request.getRequestURI()));
    }
}
