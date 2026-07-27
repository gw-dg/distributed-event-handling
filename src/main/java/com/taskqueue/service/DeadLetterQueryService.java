package com.taskqueue.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.taskqueue.dlq.DeadLetterEntry;
import com.taskqueue.model.Task;
import com.taskqueue.repo.DeadLetterRepository;
import com.taskqueue.repo.TaskRepository;

/**
 * Application service for operator-facing dead letter queue management.
 *
 * <p>From dead-letter-queues.md:
 *   "Operators need three things: a way to inspect what died, a way to replay it,
 *    and a way to discard it without losing the audit trail."
 *
 * <p>This service provides inspect and replay (redrive). It does not delete —
 * dead-letter entries are kept as an audit log even after redrive.
 *
 * <p>Redrive: creates a brand-new Task in PENDING status with a fresh UUID.
 * We generate a new id so the redriven task does not collide with the original
 * idempotency key (a re-queued task is a fresh attempt, not a duplicate).
 */
@Service
public class DeadLetterQueryService {

    /** Default page size for list queries. */
    private static final int DEFAULT_LIMIT = 50;

    private final DeadLetterRepository dlqRepository;
    private final TaskRepository taskRepository;

    public DeadLetterQueryService(
            DeadLetterRepository dlqRepository,
            TaskRepository taskRepository) {
        this.dlqRepository = dlqRepository;
        this.taskRepository = taskRepository;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /** Returns the most recently dead-lettered entries (newest first). */
    public List<DeadLetterEntry> listRecent() {
        return dlqRepository.findRecent(DEFAULT_LIMIT);
    }

    /** Returns the most recently dead-lettered entries for a specific type. */
    public List<DeadLetterEntry> listRecentByType(String type) {
        return dlqRepository.findRecentByType(type, DEFAULT_LIMIT);
    }

    /** Returns the total number of entries in the DLQ. */
    public int totalCount() {
        return dlqRepository.count();
    }

    // ── Redrive ───────────────────────────────────────────────────────────────

    /**
     * Re-queues a dead-lettered task back into the main task queue as PENDING.
     *
     * <p>The redriven task gets a fresh UUID so it does not collide with any
     * idempotency key attached to the original. The original DLQ entry is
     * stamped with {@code redrive_count + 1} for audit purposes.
     *
     * @param dlqId the surrogate primary key from the dead_letter table
     * @return the newly created Task (PENDING, fresh id)
     * @throws java.util.NoSuchElementException if no entry found with that id
     */
    public Task redrive(long dlqId) {
        DeadLetterEntry entry = dlqRepository.findById(dlqId)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No DLQ entry found with id=" + dlqId));

        // Read the original maxAttempts from properties (default 5) if not stored.
        // For simplicity, redrived tasks get 3 fresh attempts.
        Task redriven = Task.create(
                UUID.randomUUID().toString(),
                entry.type(),
                entry.payload(),
                3,              // fresh attempt budget
                entry.priority());

        taskRepository.save(redriven);
        dlqRepository.markRedriven(dlqId);

        return redriven;
    }
}
