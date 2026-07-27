package com.taskqueue.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.taskqueue.model.Task;
import com.taskqueue.repo.TaskRepository;

/**
 * Read-only application service for task state queries.
 *
 * <p>Follows Command-Query Separation: commands change state (TaskSubmissionService),
 * queries read state (here).
 */
@Service
public class TaskQueryService {

    private final TaskRepository repository;

    public TaskQueryService(TaskRepository repository) {
        this.repository = repository;
    }

    /** Finds a task by id. */
    public Optional<Task> findById(String id) {
        return repository.findById(id);
    }

    /**
     * Returns the most recent tasks across all statuses, ordered by created_at DESC.
     *
     * @param limit max rows (capped in the repository at the SQL level)
     */
    public List<Task> findRecent(int limit) {
        return repository.findRecent(Math.min(limit, 200));
    }

    /**
     * Returns the most recent tasks in the given status, ordered by created_at DESC.
     *
     * @param status the status string (e.g. "RUNNING", "PENDING")
     * @param limit  max rows
     */
    public List<Task> findByStatus(String status, int limit) {
        return repository.findByStatus(status, Math.min(limit, 200));
    }
}
