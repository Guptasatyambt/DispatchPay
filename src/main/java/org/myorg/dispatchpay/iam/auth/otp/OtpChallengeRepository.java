package org.myorg.dispatchpay.iam.auth.otp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OtpChallengeRepository {

    record ActiveChallenge(UUID id, String codeHash, int attempts, int maxAttempts, Instant createdAt,
            Instant expiresAt) {
    }

    private final JdbcClient jdbc;

    OtpChallengeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ActiveChallenge> lockActive(String mobile) {
        return jdbc.sql("""
                        SELECT id, code_hash, attempts, max_attempts, created_at, expires_at
                        FROM otp_challenge
                        WHERE mobile = :mobile AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("mobile", mobile)
                .query((rs, rowNum) -> new ActiveChallenge(
                        rs.getObject("id", UUID.class),
                        rs.getString("code_hash"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    void insert(UUID id, String mobile, String codeHash, String deviceId, int maxAttempts, Instant now,
            Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO otp_challenge
                            (id, mobile, code_hash, device_id, status, attempts, max_attempts, created_at, expires_at)
                        VALUES (:id, :mobile, :codeHash, :deviceId, 'ACTIVE', 0, :maxAttempts, :now, :expiresAt)
                        """)
                .param("id", id)
                .param("mobile", mobile)
                .param("codeHash", codeHash)
                .param("deviceId", deviceId)
                .param("maxAttempts", maxAttempts)
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    void close(UUID id, String status, Instant now) {
        jdbc.sql("UPDATE otp_challenge SET status = :status, closed_at = :now WHERE id = :id")
                .param("status", status)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    void recordFailedAttempt(UUID id, int attempts, boolean lock, Instant now) {
        jdbc.sql("""
                        UPDATE otp_challenge
                        SET attempts = :attempts,
                            status = CASE WHEN :lock THEN 'LOCKED' ELSE status END,
                            closed_at = CASE WHEN :lock THEN :now ELSE closed_at END
                        WHERE id = :id
                        """)
                .param("attempts", attempts)
                .param("lock", lock)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    int deleteCreatedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM otp_challenge WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }
}
