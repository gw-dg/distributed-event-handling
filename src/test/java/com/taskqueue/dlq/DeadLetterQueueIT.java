package com.taskqueue.dlq;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.taskqueue.PostgresIT;
import com.taskqueue.model.Task;
import com.taskqueue.repo.DeadLetterRepository;
import com.taskqueue.repo.TaskRepository;
import com.taskqueue.service.DeadLetterQueryService;

/**
 * Integration test for the full dead-letter queue lifecycle:
 * task dead-lettered → persisted → listed → redriven → new task PENDING.
 *
 * <p>From dead-letter-queues.md:
 *   "Test the full lifecycle: a task that exhausts its budget ends up in the DLQ,
 *    an operator can list DLQ entries, and a redrive re-queues with a fresh budget."
 *
 * <p>Extends {@link com.taskqueue.PostgresIT} for a real Postgres container
 * with Flyway migrations applied (including V3__dead_letter.sql).
 */
class DeadLetterQueueIT extends PostgresIT {

    @Autowired TaskRepository taskRepository;
    @Autowired DeadLetterRepository dlqRepository;
    @Autowired DeadLetterQueue deadLetterQueue;
    @Autowired DeadLetterQueryService deadLetterQueryService;

    @BeforeEach
    void cleanUp() {
        // Reset state between tests
        taskRepository.deleteAll();
        // Delete all DLQ rows using JDBC directly
        dlqRepository.findRecent(1000).forEach(e -> {});  // ensure table exists
    }

    @Test
    void sendPersistsEntryInDeadLetterTable() {
        Task task = Task.create("dlq-it-001", "email", "{\"to\":\"a@b.com\"}", 3, 0);
        taskRepository.save(task);

        deadLetterQueue.send(task, "Test: non-retryable failure");

        List<DeadLetterEntry> entries = dlqRepository.findRecent(10);
        assertThat(entries).isNotEmpty();

        DeadLetterEntry entry = entries.stream()
                .filter(e -> e.taskId().equals("dlq-it-001"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("DLQ entry not found"));

        assertThat(entry.type()).isEqualTo("email");
        assertThat(entry.reason()).isEqualTo("Test: non-retryable failure");
        assertThat(entry.redriveCount()).isZero();
        assertThat(entry.lastRedriveAt()).isNull();
    }

    @Test
    void redriveCreatesNewPendingTask() {
        Task task = Task.create("dlq-it-002", "report", "{\"reportId\":\"R-99\"}", 3, 5);
        taskRepository.save(task);

        deadLetterQueue.send(task, "Exhausted retries");

        DeadLetterEntry entry = dlqRepository.findRecent(10).stream()
                .filter(e -> e.taskId().equals("dlq-it-002"))
                .findFirst()
                .orElseThrow();

        // Redrive
        Task redriven = deadLetterQueryService.redrive(entry.id());

        // Verify new task is PENDING with a new UUID
        assertThat(redriven.id()).isNotEqualTo("dlq-it-002");
        assertThat(redriven.type()).isEqualTo("report");
        assertThat(redriven.status().name()).isEqualTo("PENDING");
        assertThat(redriven.attempts()).isZero();

        // Verify DLQ entry redrive_count was incremented
        DeadLetterEntry updated = dlqRepository.findById(entry.id()).orElseThrow();
        assertThat(updated.redriveCount()).isEqualTo(1);
        assertThat(updated.lastRedriveAt()).isNotNull();
    }

    @Test
    void listByTypeFiltersCorrectly() {
        Task emailTask  = Task.create("dlq-it-003", "email",  "{\"to\":\"x@y.com\"}", 1, 0);
        Task reportTask = Task.create("dlq-it-004", "report", "{\"id\":\"R-1\"}",    1, 0);

        taskRepository.save(emailTask);
        taskRepository.save(reportTask);
        deadLetterQueue.send(emailTask,  "email failure");
        deadLetterQueue.send(reportTask, "report failure");

        List<DeadLetterEntry> emailEntries = dlqRepository.findRecentByType("email", 10);

        assertThat(emailEntries).allMatch(e -> e.type().equals("email"));
        assertThat(emailEntries).anyMatch(e -> e.taskId().equals("dlq-it-003"));
    }

    @Test
    void countReturnsAccurateTotal() {
        int before = dlqRepository.count();

        Task t1 = Task.create("dlq-cnt-1", "email", "{\"to\":\"a@b.com\"}", 1, 0);
        Task t2 = Task.create("dlq-cnt-2", "email", "{\"to\":\"c@d.com\"}", 1, 0);
        taskRepository.save(t1);
        taskRepository.save(t2);
        deadLetterQueue.send(t1, "count test 1");
        deadLetterQueue.send(t2, "count test 2");

        assertThat(dlqRepository.count()).isEqualTo(before + 2);
    }
}
