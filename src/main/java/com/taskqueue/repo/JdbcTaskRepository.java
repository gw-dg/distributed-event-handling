package com.taskqueue.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;

/**
 * JDBC adapter implementing {@link TaskRepository}.
 *
 * <p>This is the only class in the application that knows SQL.
 * It is the outbound adapter for the persistence port (hexagonal-architecture.md).
 *
 * <p>Key design decisions (from ch04 + task-queues.md):
 * <ul>
 *   <li>Uses {@code JdbcTemplate} for resource management and exception translation.
 *   <li>{@code pollDue()} wraps both SELECT and UPDATE in one {@code @Transactional}
 *       method — the lock must be held until the UPDATE commits.
 *   <li>{@code FOR UPDATE SKIP LOCKED} lets concurrent workers avoid blocking each other:
 *       each transaction skips rows already locked by another transaction.
 *   <li>{@code save()} uses UPSERT so callers don't distinguish insert from update.
 *   <li>Instants are converted to {@code OffsetDateTime} (UTC) for PostgreSQL TIMESTAMPTZ compatibility.
 * </ul>
 */
@Repository
public class JdbcTaskRepository implements TaskRepository {

    private final JdbcTemplate jdbc;

    public JdbcTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─── Write operations ───────────────────────────────────────────────────

    @Override
    public void save(Task task) {
        jdbc.update("""
                INSERT INTO tasks
                    (id, type, payload, status, attempts, max_attempts,
                     created_at, scheduled_at, priority, last_error, version)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (id) DO UPDATE SET
                    status       = EXCLUDED.status,
                    attempts     = EXCLUDED.attempts,
                    scheduled_at = EXCLUDED.scheduled_at,
                    last_error   = EXCLUDED.last_error,
                    version      = tasks.version + 1
                """,
                task.id(),
                task.type(),
                task.payload(),
                task.status().name(),
                task.attempts(),
                task.maxAttempts(),
                toOffsetDateTime(task.createdAt()),
                toOffsetDateTime(task.scheduledAt()),
                task.priority(),
                task.lastError());
    }

    @Override
    public boolean saveWithIdempotencyKey(Task task, String idempotencyKey) {
        int rows = jdbc.update("""
                INSERT INTO tasks
                    (id, type, payload, status, attempts, max_attempts,
                     created_at, scheduled_at, priority, last_error, version, idempotency_key)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL
                DO NOTHING
                """,
                task.id(),
                task.type(),
                task.payload(),
                task.status().name(),
                task.attempts(),
                task.maxAttempts(),
                toOffsetDateTime(task.createdAt()),
                toOffsetDateTime(task.scheduledAt()),
                task.priority(),
                task.lastError(),
                idempotencyKey);
        return rows == 1;
    }

    @Override
    public void updateStatus(String id, TaskStatus status) {
        jdbc.update("""
                UPDATE tasks
                SET status = ?, version = version + 1
                WHERE id = ?
                """,
                status.name(), id);
    }

    @Override
    public void deleteAll() {
        jdbc.update("DELETE FROM tasks");
    }

    // ─── Read operations ────────────────────────────────────────────────────

    @Override
    public Optional<Task> findById(String id) {
        List<Task> result = jdbc.query("""
                SELECT id, type, payload::text, status, attempts, max_attempts,
                       created_at, scheduled_at, priority, last_error
                FROM tasks
                WHERE id = ?
                """,
                taskRowMapper(), id);
        return result.stream().findFirst();
    }

    @Override
    public Optional<Task> findByIdempotencyKey(String idempotencyKey) {
        List<Task> result = jdbc.query("""
                SELECT id, type, payload::text, status, attempts, max_attempts,
                       created_at, scheduled_at, priority, last_error
                FROM tasks
                WHERE idempotency_key = ?
                """,
                taskRowMapper(), idempotencyKey);
        return result.stream().findFirst();
    }

    // ─── Leasing (hot path) ─────────────────────────────────────────────────

    @Override
    @Transactional
    public List<Task> pollDue(int limit) {
        List<Task> tasks = jdbc.query("""
                SELECT id, type, payload::text, status, attempts, max_attempts,
                       created_at, scheduled_at, priority, last_error
                FROM tasks
                WHERE status IN ('PENDING', 'RETRYING', 'SCHEDULED')
                  AND scheduled_at <= NOW()
                ORDER BY priority DESC, created_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                taskRowMapper(), limit);

        if (tasks.isEmpty()) {
            return List.of();
        }

        for (Task task : tasks) {
            jdbc.update("""
                    UPDATE tasks
                    SET status = 'RUNNING', attempts = attempts + 1, version = version + 1
                    WHERE id = ?
                    """,
                    task.id());
        }

        return tasks.stream()
                .map(Task::markRunning)
                .toList();
    }

    @Override
    @Transactional
    public int reclaimStuckTasks(long visibilityTimeoutSeconds) {
        return jdbc.update("""
                UPDATE tasks
                SET status = 'RETRYING',
                    last_error = 'Reclaimed by StuckTaskReaper: worker timeout',
                    version = version + 1
                WHERE status = 'RUNNING'
                  AND scheduled_at < NOW() - (? * INTERVAL '1 second')
                """,
                visibilityTimeoutSeconds);
    }

    @Override
    public int pendingCount() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tasks
                WHERE status IN ('PENDING', 'RETRYING', 'SCHEDULED')
                """, Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<Task> findRecent(int limit) {
        return jdbc.query("""
                SELECT id, type, payload, status, attempts, max_attempts,
                       created_at, scheduled_at, priority, last_error
                FROM tasks
                ORDER BY created_at DESC
                LIMIT ?
                """,
                taskRowMapper(),
                limit);
    }

    @Override
    public List<Task> findByStatus(String status, int limit) {
        return jdbc.query("""
                SELECT id, type, payload, status, attempts, max_attempts,
                       created_at, scheduled_at, priority, last_error
                FROM tasks
                WHERE status = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
                taskRowMapper(),
                status, limit);
    }

    // ─── Helpers & Row mapping ──────────────────────────────────────────────

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    private RowMapper<Task> taskRowMapper() {
        return new TaskRowMapper();
    }

    private static final class TaskRowMapper implements RowMapper<Task> {

        @Override
        public Task mapRow(ResultSet rs, int rowNum) throws SQLException {
            OffsetDateTime created   = rs.getObject("created_at", OffsetDateTime.class);
            OffsetDateTime scheduled = rs.getObject("scheduled_at", OffsetDateTime.class);

            return Task.reconstitute(
                    rs.getString("id"),
                    rs.getString("type"),
                    rs.getString("payload"),
                    TaskStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getInt("max_attempts"),
                    created != null ? created.toInstant() : Instant.now(),
                    scheduled != null ? scheduled.toInstant() : Instant.now(),
                    rs.getInt("priority"),
                    rs.getString("last_error"));
        }
    }
}
