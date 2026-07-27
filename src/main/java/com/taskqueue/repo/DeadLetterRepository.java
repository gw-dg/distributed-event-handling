package com.taskqueue.repo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.taskqueue.dlq.DeadLetterEntry;

/**
 * JDBC repository for the {@code dead_letter} table.
 *
 * <p>Provides insert (called by {@link com.taskqueue.dlq.PostgresDeadLetterQueue}),
 * list, and redrive-count-update operations used by the admin API.
 *
 * <p>From dead-letter-queues.md:
 *   "A DLQ without a management API is a black hole."
 * The redrive operation copies the entry back to the main tasks table via the
 * {@link TaskRepository} — this class only handles the DLQ side.
 */
@Repository
public class DeadLetterRepository {

    private final JdbcTemplate jdbc;

    public DeadLetterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Row mapper ────────────────────────────────────────────────────────────

    private static final RowMapper<DeadLetterEntry> ROW_MAPPER = (rs, rowNum) ->
            new DeadLetterEntry(
                    rs.getLong("id"),
                    rs.getString("task_id"),
                    rs.getString("type"),
                    rs.getString("payload"),
                    rs.getString("reason"),
                    rs.getInt("original_attempts"),
                    rs.getInt("priority"),
                    toInstant(rs.getObject("failed_at", OffsetDateTime.class)),
                    rs.getInt("redrive_count"),
                    toInstant(rs.getObject("last_redrive_at", OffsetDateTime.class))
            );

    private static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Persists a dead-lettered task.
     *
     * @param taskId            original task id
     * @param type              task type
     * @param payload           original JSON payload
     * @param reason            human-readable reason for dead-lettering
     * @param originalAttempts  how many times it was attempted
     * @param priority          original task priority
     */
    public void insert(
            String taskId,
            String type,
            String payload,
            String reason,
            int originalAttempts,
            int priority) {

        jdbc.update("""
                INSERT INTO dead_letter
                    (task_id, type, payload, reason, original_attempts, priority)
                VALUES (?, ?, ?::jsonb, ?, ?, ?)
                """,
                taskId,
                type,
                payload,
                reason,
                originalAttempts,
                priority);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns up to {@code limit} entries ordered newest-first.
     *
     * @param limit max number of rows to return
     */
    public List<DeadLetterEntry> findRecent(int limit) {
        return jdbc.query("""
                SELECT id, task_id, type, payload, reason,
                       original_attempts, priority, failed_at,
                       redrive_count, last_redrive_at
                FROM dead_letter
                ORDER BY failed_at DESC
                LIMIT ?
                """, ROW_MAPPER, limit);
    }

    /**
     * Returns up to {@code limit} entries for a specific task type, newest-first.
     */
    public List<DeadLetterEntry> findRecentByType(String type, int limit) {
        return jdbc.query("""
                SELECT id, task_id, type, payload, reason,
                       original_attempts, priority, failed_at,
                       redrive_count, last_redrive_at
                FROM dead_letter
                WHERE type = ?
                ORDER BY failed_at DESC
                LIMIT ?
                """, ROW_MAPPER, type, limit);
    }

    /** Finds a specific DLQ entry by its surrogate id. */
    public Optional<DeadLetterEntry> findById(long id) {
        List<DeadLetterEntry> rows = jdbc.query("""
                SELECT id, task_id, type, payload, reason,
                       original_attempts, priority, failed_at,
                       redrive_count, last_redrive_at
                FROM dead_letter
                WHERE id = ?
                """, ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Returns the total number of entries in the DLQ (used by health indicator). */
    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter", Integer.class);
        return n == null ? 0 : n;
    }

    // ── Redrive ───────────────────────────────────────────────────────────────

    /**
     * Stamps the entry as redriven so operators can audit repeated redrive attempts.
     *
     * @param id DLQ entry surrogate id
     */
    public void markRedriven(long id) {
        jdbc.update("""
                UPDATE dead_letter
                SET redrive_count    = redrive_count + 1,
                    last_redrive_at  = ?
                WHERE id = ?
                """,
                OffsetDateTime.now(ZoneOffset.UTC),
                id);
    }
}
