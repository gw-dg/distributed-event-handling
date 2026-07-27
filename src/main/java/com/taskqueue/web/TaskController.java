package com.taskqueue.web;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.taskqueue.model.Task;
import com.taskqueue.service.TaskNotFoundException;
import com.taskqueue.service.TaskQueryService;
import com.taskqueue.service.TaskSubmissionService;

/**
 * HTTP inbound adapter for the Task Queue API.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /tasks}     — 202 Accepted + Location header
 *   <li>{@code GET  /tasks}     — list recent tasks (default 100, optional status filter)
 *   <li>{@code GET  /tasks/{id}} — single task by id
 * </ul>
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
     * Lists the most recent tasks, optionally filtered by status.
     *
     * <p>Used by the frontend dashboard to populate the live task table.
     *
     * @param status optional status filter (e.g. "RUNNING", "PENDING")
     * @param limit  max rows to return (default 100)
     * @return 200 OK with list of task responses
     */
    @GetMapping
    public ResponseEntity<List<TaskResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {

        List<Task> tasks = (status != null && !status.isBlank())
                ? queryService.findByStatus(status, limit)
                : queryService.findRecent(limit);

        return ResponseEntity.ok(tasks.stream().map(TaskResponse::from).toList());
    }

    /**
     * Returns the current state of a single task.
     *
     * @param id the task UUID string
     * @return 200 OK or 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> getById(@PathVariable String id) {
        return queryService.findById(id)
                .map(TaskResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }
}
