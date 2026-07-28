package com.taskqueue.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link OutboxRepository}.
 *
 * <p>From ch04 (Spring Boot JDBC): uses {@link JdbcTemplate} — low ceremony,
 * easy to test, no magic. Relies on {@code V4__outbox.sql} migration.
 *
 * <p>The key design decisions:
 * <ul>
 *   <li>{@link #fetchUnpublished} uses {@code FOR UPDATE SKIP LOCKED} — concurrent
 *       relay instances grab disjoint rows atomically. No row is ever fetched by two
 *       relay threads simultaneously.</li>
 *   <li>{@link #insert} uses {@code RETURNING id, created_at} to populate the
 *       generated UUID and timestamp on the returned record.</li>
 * </ul>
 */
@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcOutboxRepository.class);

    private static final RowMapper<OutboxRecord> ROW_MAPPER = (rs, n) -> new OutboxRecord(
            rs.getString("id"),
            rs.getString("aggregate_id"),
            rs.getString("event_type"),
            rs.getString("payload"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("published_at")));

    private final JdbcTemplate jdbc;

    public JdbcOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OutboxRecord insert(OutboxRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO outbox (aggregate_id, event_type, payload) "
                    + "VALUES (?, ?, ?) RETURNING id, created_at",
                    new String[]{"id", "created_at"});
            ps.setString(1, record.aggregateId());
            ps.setString(2, record.eventType());
            ps.setString(3, record.payload());
            return ps;
        }, keyHolder);

        Map<String, Object> keys = keyHolder.getKeys();
        String id         = keys.get("id").toString();
        Instant createdAt = ((Timestamp) keys.get("created_at")).toInstant();

        return new OutboxRecord(id, record.aggregateId(), record.eventType(),
                record.payload(), createdAt, null);
    }

    @Override
    public List<OutboxRecord> fetchUnpublished(int limit) {
        // FOR UPDATE SKIP LOCKED: concurrent relay instances get disjoint rows
        return jdbc.query(
                "SELECT id, aggregate_id, event_type, payload, created_at, published_at "
                + "FROM outbox "
                + "WHERE published_at IS NULL "
                + "ORDER BY created_at ASC "
                + "LIMIT ? "
                + "FOR UPDATE SKIP LOCKED",
                ROW_MAPPER,
                limit);
    }

    @Override
    public void markPublished(String id) {
        int updated = jdbc.update(
                "UPDATE outbox SET published_at = now() WHERE id = ?::uuid",
                id);
        if (updated == 0) {
            log.warn("[Outbox] markPublished found no row for id={}", id);
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
