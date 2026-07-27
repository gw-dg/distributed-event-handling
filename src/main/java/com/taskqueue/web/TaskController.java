package com.taskqueue.web;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taskqueue.model.Task;
import com.taskqueue.service.TaskNotFoundException;
import com.taskqueue.service.TaskQueryService;
import com.taskqueue.service.TaskSubmissionService;

/**
 * HTTP inbound adapter for the Task Queue API.
 *
 * <p>From ch02: "The controller is an adapter, not the application.
 * It translates HTTP into a use case call and translates the result back into HTTP."
 *
 * <p>This controller has zero business logic. Every decision is in the services.
 * The controller only:
 * <ul>
 *   <li>Parses and validates the request (via {@code @Valid}).
 *   <li>Delegates to the application service.
 *   <li>Maps the domain result to an HTTP response.
 * </ul>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /tasks} — 202 Accepted + Location header (async task queue semantics)
 *   <li>{@code GET /tasks/{id}} — 200 OK or 404 Not Found
 * </ul>
 *
 * <p>From ch02: "Use 202 Accepted when workers run asynchronously and include a
 * Location header for status lookup." We return 202 because execution is deferred;
 * the client must poll GET /tasks/{id} for the final outcome.
 */
@RestController
@RequestMapping("/tasks")
public final class TaskController {

    private final TaskSubmissionService submissionService;
    private final TaskQueryService queryService;

    public TaskController(
            TaskSubmissionService submissionService,
            TaskQueryService queryService) {
        this.submissionService = submissionService;
        this.queryService      = queryService;
    }

    /**
     * Submits a new task for asynchronous execution.
     *
     * <p>{@code @Valid} triggers Bean Validation on the request body.
     * If validation fails, Spring throws {@code MethodArgumentNotValidException}
     * which {@code ApiExceptionHandler} maps to a 400 response.
     *
     * @param request validated inbound DTO
     * @return 202 Accepted with Location header and task body
     */
    @PostMapping
    public ResponseEntity<TaskResponse> submit(
            @Valid @RequestBody SubmitTaskRequest request) {

        Task task = submissionService.submit(request);
        URI location = URI.create("/tasks/" + task.id());

        return ResponseEntity.accepted()
                .location(location)
                .body(TaskResponse.from(task));
    }

    /**
     * Returns the current state of a task.
     *
     * @param id the task UUID string
     * @return 200 OK with task body, or 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> getById(@PathVariable String id) {
        return queryService.findById(id)
                .map(TaskResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }
}
