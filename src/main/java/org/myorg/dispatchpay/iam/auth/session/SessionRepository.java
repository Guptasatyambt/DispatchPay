package org.myorg.dispatchpay.iam.auth.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SessionRepository {

    /** A refresh token joined with its session and user, locked for rotation. */
    record LockedRefreshToken(
            UUID tokenId,
            UUID sessionId,
            UUID userId,
            String userStatus,
            Instant tokenExpiresAt,
            Instant usedAt,
            Instant sessionExpiresAt,
            Instant sessionRevokedAt) {
    }

    private final JdbcClient jdbc;

    SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertSession(UUID id, UUID userId, String deviceId, String deviceName, Instant now, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO auth_session
                            (id, user_id, device_id, device_name, created_at, last_refreshed_at, expires_at)
                        VALUES (:id, :userId, :deviceId, :deviceName, :now, :now, :expiresAt)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("deviceId", deviceId)
                .param("deviceName", deviceName)
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    void insertRefreshToken(UUID id, UUID sessionId, String tokenHash, Instant now, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO refresh_token (id, session_id, token_hash, created_at, expires_at)
                        VALUES (:id, :sessionId, :tokenHash, :now, :expiresAt)
                        """)
                .param("id", id)
                .param("sessionId", sessionId)
                .param("tokenHash", tokenHash)
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    /**
     * Locks the token and its session. Concurrent refreshes with the same token serialize here;
     * the loser sees the token as used and is treated as a reuse.
     */
    Optional<LockedRefreshToken> lockByTokenHash(String tokenHash) {
        return jdbc.sql("""
                        SELECT rt.id AS token_id, rt.session_id, s.user_id, u.status AS user_status,
                               rt.expires_at AS token_expires_at, rt.used_at,
                               s.expires_at AS session_expires_at, s.revoked_at
                        FROM refresh_token rt
                        JOIN auth_session s ON s.id = rt.session_id
                        JOIN user_account u ON u.id = s.user_id
                        WHERE rt.token_hash = :tokenHash
                        FOR UPDATE OF rt, s
                        """)
                .param("tokenHash", tokenHash)
                .query((rs, rowNum) -> new LockedRefreshToken(
                        rs.getObject("token_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("user_status"),
                        rs.getTimestamp("token_expires_at").toInstant(),
                        toInstant(rs.getTimestamp("used_at")),
                        rs.getTimestamp("session_expires_at").toInstant(),
                        toInstant(rs.getTimestamp("revoked_at"))))
                .optional();
    }

    void markTokenUsed(UUID tokenId, Instant now) {
        jdbc.sql("UPDATE refresh_token SET used_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", tokenId)
                .update();
    }

    void touchSession(UUID sessionId, Instant now) {
        jdbc.sql("UPDATE auth_session SET last_refreshed_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", sessionId)
                .update();
    }

    void revokeSession(UUID sessionId, String reason, Instant now) {
        jdbc.sql("""
                        UPDATE auth_session SET revoked_at = :now, revoke_reason = :reason
                        WHERE id = :id AND revoked_at IS NULL
                        """)
                .param("now", Timestamp.from(now))
                .param("reason", reason)
                .param("id", sessionId)
                .update();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
