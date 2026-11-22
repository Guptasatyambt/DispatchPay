package org.myorg.dispatchpay.platform.idempotency;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class IdempotencyRepository {

    record StoredRecord(String requestHash, String responseBody) {
    }

    private final JdbcClient jdbc;

    IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key. On a concurrent claim PostgreSQL blocks until the other transaction ends,
     * then reports a conflict (it committed) or lets this insert proceed (it rolled back).
     */
    boolean tryInsert(UUID id, String scope, String operation, String key, String requestHash,
            Instant now, Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO idempotency_record
                            (id, scope, operation, idempotency_key, request_hash, created_at, expires_at)
                        VALUES (:id, :scope, :operation, :key, :hash, :now, :expiresAt)
                        ON CONFLICT (scope, operation, idempotency_key) DO NOTHING
                        """)
                .param("id", id)
                .param("scope", scope)
                .param("operation", operation)
                .param("key", key)
                .param("hash", requestHash)
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update() == 1;
    }

    Optional<StoredRecord> find(String scope, String operation, String key) {
        return jdbc.sql("""
                        SELECT request_hash, response_body::text AS response_body
                        FROM idempotency_record
                        WHERE scope = :scope AND operation = :operation AND idempotency_key = :key
                        """)
                .param("scope", scope)
                .param("operation", operation)
                .param("key", key)
                .query((rs, rowNum) -> new StoredRecord(rs.getString("request_hash"), rs.getString("response_body")))
                .optional();
    }

    void complete(UUID id, String responseBody, Instant now) {
        jdbc.sql("""
                        UPDATE idempotency_record
                        SET response_body = CAST(:body AS jsonb), completed_at = :now
                        WHERE id = :id
                        """)
                .param("body", responseBody)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    void deleteExpired(String scope, String operation, String key, Instant now) {
        jdbc.sql("""
                        DELETE FROM idempotency_record
                        WHERE scope = :scope AND operation = :operation AND idempotency_key = :key
                          AND expires_at <= :now
                        """)
                .param("scope", scope)
                .param("operation", operation)
                .param("key", key)
                .param("now", Timestamp.from(now))
                .update();
    }

    int deleteAllExpired(Instant now) {
        return jdbc.sql("DELETE FROM idempotency_record WHERE expires_at <= :now")
                .param("now", Timestamp.from(now))
                .update();
    }
}
