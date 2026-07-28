package com.taskqueue.outbox;

import java.util.List;

/**
 * Outbound port for the transactional outbox table.
 *
 * <p>From hexagonal-architecture.md: the application core depends on this interface;
 * only {@link JdbcOutboxRepository} knows SQL. Swapping to a different persistence
 * technology only touches the implementation.
 */
public interface OutboxRepository {

    /**
     * Inserts a new outbox row within the caller's transaction.
     *
     * <p>Must be called inside the same {@code @Transactional} scope as the
     * domain change it accompanies (e.g., the task submission or status update).
     *
     * @param record the row to insert; {@code id} and {@code createdAt} may be null
     *               (the database sets them via defaults)
     * @return the persisted record with {@code id} and {@code createdAt} populated
     */
    OutboxRecord insert(OutboxRecord record);

    /**
     * Returns up to {@code limit} unpublished rows in insertion order.
     *
     * <p>Uses {@code SELECT ... FOR UPDATE SKIP LOCKED} so concurrent relay
     * instances grab different rows — no double-publish.
     *
     * @param limit max rows to return; must be >= 1
     * @return list of unpublished rows, oldest first
     */
    List<OutboxRecord> fetchUnpublished(int limit);

    /**
     * Marks a row as published.
     *
     * <p>Sets {@code published_at = now()} for the given id. Called by the relay
     * after successfully forwarding the row to the broker.
     *
     * @param id the outbox row UUID
     */
    void markPublished(String id);
}
