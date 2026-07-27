package com.taskqueue.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.taskqueue.model.Task;
import com.taskqueue.repo.TaskRepository;

/**
 * Read-only application service for task state queries.
 *
 * <p>Separated from {@link TaskSubmissionService} following the
 * Command-Query Separation principle: commands change state, queries read state.
 * The controller delegates write operations to {@code TaskSubmissionService}
 * and read operations here.
 *
 * <p>Future: add caching here with {@code @Cacheable} (Phase 3 ch06 material)
 * to reduce DB reads for hot GET /tasks/{id} endpoints.
 */
@Service
public class TaskQueryService {

    private final TaskRepository repository;

    public TaskQueryService(TaskRepository repository) {
        this.repository = repository;
    }

    /**
     * Finds a task by id.
     *
     * @param id the task UUID string
     * @return the task if found, or empty
     */
    public Optional<Task> findById(String id) {
        return repository.findById(id);
    }
}
