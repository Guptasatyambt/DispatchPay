package org.myorg.dispatchpay.platform.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OutboxRepository {

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, String aggregateType, String aggregateId, String eventType, String payload,
            UUID tenantId, String correlationId, String metadata, Instant now) {
        jdbc.sql("""
                        INSERT INTO outbox_event
                            (id, aggregate_type, aggregate_id, event_type, payload, tenant_id, correlation_id,
                             metadata, status, attempts, next_attempt_at, created_at)
                        VALUES (:id, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb), :tenantId,
                                :correlationId, CAST(:metadata AS jsonb), 'PENDING', 0, :now, :now)
                        """)
                .param("id", id)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payload)
                .param("tenantId", tenantId)
                .param("correlationId", correlationId)
                .param("metadata", metadata)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Locks the next due event; rows locked by other workers are skipped, never double-claimed. */
    Optional<OutboxEvent> lockNextDue(Instant now) {
        return jdbc.sql("""
                        SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload, tenant_id,
                               correlation_id, metadata::text AS metadata, attempts, created_at
                        FROM outbox_event
                        WHERE status = 'PENDING' AND next_attempt_at <= :now
                        ORDER BY next_attempt_at, created_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("now", Timestamp.from(now))
                .query(OutboxRepository::mapEvent)
                .optional();
    }

    void markPublished(UUID id, Instant now) {
        jdbc.sql("UPDATE outbox_event SET status = 'PUBLISHED', published_at = :now, last_error = NULL WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    /** Records a failed delivery; ignored if another worker already finished the event. */
    void markFailed(UUID id, int attempts, boolean dead, Instant nextAttemptAt, String error) {
        jdbc.sql("""
                        UPDATE outbox_event
                        SET attempts = :attempts, status = :status, next_attempt_at = :nextAttemptAt, last_error = :error
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("attempts", attempts)
                .param("status", dead ? "DEAD" : "PENDING")
                .param("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .param("error", error)
                .param("id", id)
                .update();
    }

    private static OutboxEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEvent(
                rs.getObject("id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("correlation_id"),
                rs.getString("metadata"),
                rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant());
    }
}
