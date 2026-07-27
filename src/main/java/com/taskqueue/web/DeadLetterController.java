package com.taskqueue.web;

import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.taskqueue.dlq.DeadLetterEntry;
import com.taskqueue.model.Task;
import com.taskqueue.service.DeadLetterQueryService;

/**
 * Operator REST API for the Dead Letter Queue.
 *
 * <p>From dead-letter-queues.md: "Operators need a management interface.
 * Without it, a DLQ is a graveyard that silently fills up and eventually
 * causes alerts about growing queue depth or customer complaints."
 *
 * <p>Endpoints:
 * <pre>
 *   GET  /tasks/dlq                     — list recent DLQ entries
 *   GET  /tasks/dlq?type=email          — list entries filtered by type
 *   POST /tasks/dlq/{id}/redrive        — re-queue a specific entry as PENDING
 * </pre>
 *
 * <p>These are operator/admin endpoints. In production they should be protected
 * behind a role check (e.g., {@code SCOPE_tasks:admin}).
 */
@RestController
@RequestMapping({"/tasks/dlq", "/dlq"})
public class DeadLetterController {

    private final DeadLetterQueryService service;

    public DeadLetterController(DeadLetterQueryService service) {
        this.service = service;
    }

    /**
     * Lists the most recently dead-lettered tasks (newest first, default limit 50).
     *
     * <p>Optional {@code type} filter: {@code GET /tasks/dlq?type=email}
     *
     * @param type optional task type filter
     * @return 200 with list of DLQ entries
     */
    @GetMapping
    public ResponseEntity<List<DeadLetterEntry>> list(
            @RequestParam(required = false) String type) {

        List<DeadLetterEntry> entries = (type != null && !type.isBlank())
                ? service.listRecentByType(type)
                : service.listRecent();

        return ResponseEntity.ok(entries);
    }

    /**
     * Re-queues a dead-lettered task back into the main task queue.
     *
     * <p>The redriven task receives a fresh UUID and 3 attempt budget.
     * The original DLQ entry is preserved for audit; its {@code redrive_count}
     * is incremented.
     *
     * @param id surrogate primary key from the dead_letter table
     * @return 201 Created with Location pointing at the new task
     */
    @PostMapping("/{id}/redrive")
    public ResponseEntity<Task> redrive(@PathVariable long id) {
        try {
            Task redriven = service.redrive(id);
            URI location = URI.create("/tasks/" + redriven.id());
            return ResponseEntity.created(location).body(redriven);
        } catch (NoSuchElementException ex) {
            return ResponseEntity.notFound().build();
        }
    }
}
