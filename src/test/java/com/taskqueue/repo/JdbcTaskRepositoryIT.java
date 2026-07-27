package com.taskqueue.repo;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.taskqueue.PostgresIT;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for {@link JdbcTaskRepository} against a real PostgreSQL database.
 *
 * <p>From ch05: "Do not mock Postgres for SQL behavior. The locking, JSONB,
 * indexes, and timestamp behavior are exactly what you need to test."
 *
 * <p>Each test gets a clean database state via {@code @BeforeEach} DELETE.
 */
class JdbcTaskRepositoryIT extends PostgresIT {

    @Autowired
    private TaskRepository repository;

    @BeforeEach
    void cleanUp() {
        // Clean slate before each test — Flyway has already created the schema
        ((JdbcTaskRepository) repository).deleteAll();
    }

    // ── save + findById ─────────────────────────────────────────────────────

    @Test
    void savesAndFindsById() {
        Task task = sampleTask("t-1", "email");
        repository.save(task);

        Optional<Task> found = repository.findById("t-1");
        assertThat(found).isPresent();
        assertThat(found.get().type()).isEqualTo("email");
        assertThat(found.get().status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void saveIsIdempotentForSameId() {
        Task task = sampleTask("t-2", "email");
        repository.save(task);

        // Save same task again — should not throw (upsert)
        assertThatCode(() -> repository.save(task)).doesNotThrowAnyException();

        // Only one row exists
        assertThat(repository.findById("t-2")).isPresent();
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertThat(repository.findById("no-such-id")).isEmpty();
    }

    // ── idempotency key ─────────────────────────────────────────────────────

    @Test
    void saveWithIdempotencyKeyDeduplicatesDuplicateKey() {
        Task task1 = sampleTask("t-3", "email");
        boolean inserted = repository.saveWithIdempotencyKey(task1, "key-abc");
        assertThat(inserted).isTrue();

        // Second save with same key — should return false (not inserted)
        Task task2 = sampleTask("t-4", "email");
        boolean duplicate = repository.saveWithIdempotencyKey(task2, "key-abc");
        assertThat(duplicate).isFalse();

        // Only t-3 should exist
        assertThat(repository.findByIdempotencyKey("key-abc")).isPresent();
        assertThat(repository.findByIdempotencyKey("key-abc").get().id()).isEqualTo("t-3");
    }

    @Test
    void findByIdempotencyKeyReturnsEmptyForUnknownKey() {
        assertThat(repository.findByIdempotencyKey("unknown-key")).isEmpty();
    }

    // ── pollDue ─────────────────────────────────────────────────────────────

    @Test
    void pollDueReturnsTasksInPriorityOrder() {
        Task low  = sampleTask("low",  "email").withPriority(1);
        Task high = sampleTask("high", "email").withPriority(10);
        Task mid  = sampleTask("mid",  "email").withPriority(5);

        repository.save(low);
        repository.save(high);
        repository.save(mid);

        List<Task> polled = repository.pollDue(3);
        assertThat(polled).hasSize(3);
        assertThat(polled.get(0).id()).isEqualTo("high");  // highest priority first
        assertThat(polled.get(1).id()).isEqualTo("mid");
        assertThat(polled.get(2).id()).isEqualTo("low");
    }

    @Test
    void pollDueTransitionsTasksToRunning() {
        repository.save(sampleTask("t-run", "email"));

        List<Task> polled = repository.pollDue(1);
        assertThat(polled).hasSize(1);
        assertThat(polled.get(0).status()).isEqualTo(TaskStatus.RUNNING);

        // Row in DB is also RUNNING
        Optional<Task> found = repository.findById("t-run");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void pollDueReturnsEmptyWhenNoTasksDue() {
        assertThat(repository.pollDue(10)).isEmpty();
    }

    // ── updateStatus ────────────────────────────────────────────────────────

    @Test
    void updateStatusChangesTaskStatus() {
        repository.save(sampleTask("t-upd", "report"));
        repository.updateStatus("t-upd", TaskStatus.SUCCEEDED);

        assertThat(repository.findById("t-upd").get().status())
                .isEqualTo(TaskStatus.SUCCEEDED);
    }

    // ── reclaimStuckTasks ───────────────────────────────────────────────────

    @Test
    void reclaimStuckTasksResetsLongRunningTasks() throws InterruptedException {
        repository.save(sampleTask("t-stuck", "email"));
        repository.pollDue(1);  // transitions to RUNNING

        // reclaim with 0s timeout — any RUNNING task is "stuck"
        int reclaimed = repository.reclaimStuckTasks(0L);
        assertThat(reclaimed).isEqualTo(1);

        assertThat(repository.findById("t-stuck").get().status())
                .isEqualTo(TaskStatus.RETRYING);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Task sampleTask(String id, String type) {
        return Task.create(id, type, "{\"test\":true}", 3, 0);
    }
}
