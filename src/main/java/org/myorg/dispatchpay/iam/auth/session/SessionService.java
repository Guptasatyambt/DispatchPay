package org.myorg.dispatchpay.iam.auth.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.myorg.dispatchpay.iam.auth.AuthProperties;
import org.myorg.dispatchpay.iam.user.UserAccount;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Device sessions with rotating, single-use refresh tokens. Presenting an already-used refresh
 * token is treated as token theft: the whole session is revoked, so both the attacker and the
 * legitimate client must sign in again.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final int REFRESH_TOKEN_BYTES = 32;

    static final String REVOKED_REFRESH_REUSE = "REFRESH_TOKEN_REUSE";
    static final String REVOKED_ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    static final String REVOKED_BY_USER = "USER_REVOKED";

    private enum Rejection { INVALID, DISABLED }

    private record RefreshOutcome(SessionTokens tokens, Rejection rejection) {
    }

    private final SessionRepository sessions;
    private final AccessTokenIssuer accessTokens;
    private final RateLimiter rateLimiter;
    private final AuthProperties.Session properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SessionService(SessionRepository sessions, AccessTokenIssuer accessTokens, RateLimiter rateLimiter,
            AuthProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.sessions = sessions;
        this.accessTokens = accessTokens;
        this.rateLimiter = rateLimiter;
        this.properties = properties.session();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Opens a session for a freshly authenticated user, inside the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionTokens open(UserAccount user, String deviceId, String deviceName) {
        Instant now = clock.instant();
        UUID sessionId = UUID.randomUUID();
        Instant sessionExpiresAt = now.plus(properties.maxLifetime());
        sessions.insertSession(sessionId, user.id(), deviceId, deviceName, now, sessionExpiresAt);
        return issueTokens(user.id(), sessionId, sessionExpiresAt, now);
    }

    /**
     * Exchanges a refresh token for a new access/refresh pair. Rotation is atomic: the old token is
     * marked used and the new one stored in the same transaction under a row lock.
     */
    public SessionTokens refresh(String rawRefreshToken, String clientIp) {
        rateLimiter.consume("token-refresh:ip", clientIp, properties.refreshPerIp());
        RefreshOutcome outcome = transactionTemplate.execute(status -> rotate(rawRefreshToken));
        if (outcome.rejection() == Rejection.DISABLED) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED, "This account is disabled");
        }
        if (outcome.rejection() == Rejection.INVALID) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "Refresh token is invalid or expired");
        }
        return outcome.tokens();
    }

    private RefreshOutcome rotate(String rawRefreshToken) {
        Instant now = clock.instant();
        var locked = sessions.lockByTokenHash(sha256(rawRefreshToken)).orElse(null);
        if (locked == null) {
            return new RefreshOutcome(null, Rejection.INVALID);
        }
        if (locked.sessionRevokedAt() != null || !locked.sessionExpiresAt().isAfter(now)) {
            return new RefreshOutcome(null, Rejection.INVALID);
        }
        if (locked.usedAt() != null) {
            log.warn("Refresh token reuse detected; revoking session {} of user {}",
                    locked.sessionId(), locked.userId());
            sessions.revokeSession(locked.sessionId(), REVOKED_REFRESH_REUSE, now);
            return new RefreshOutcome(null, Rejection.INVALID);
        }
        if (!locked.tokenExpiresAt().isAfter(now)) {
            return new RefreshOutcome(null, Rejection.INVALID);
        }
        if (!UserAccount.Status.ACTIVE.name().equals(locked.userStatus())) {
            sessions.revokeSession(locked.sessionId(), REVOKED_ACCOUNT_DISABLED, now);
            return new RefreshOutcome(null, Rejection.DISABLED);
        }

        sessions.markTokenUsed(locked.tokenId(), now);
        sessions.touchSession(locked.sessionId(), now);
        return new RefreshOutcome(issueTokens(locked.userId(), locked.sessionId(), locked.sessionExpiresAt(), now),
                null);
    }

    /**
     * Signs one of the user's devices out. Revoking an already-revoked or expired session of the
     * user is a no-op; a session the user does not own is reported as not found.
     */
    @Transactional
    public List<UUID> revoke(UUID userId, UUID sessionId) {
        List<UUID> revoked = sessions.revokeUserSession(userId, sessionId, REVOKED_BY_USER, clock.instant());
        if (revoked.isEmpty() && !sessions.existsForUser(sessionId, userId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Session not found");
        }
        log.info("User {} revoked session(s) {}", userId, revoked);
        return revoked;
    }

    /** Signs the user out of every device, including the calling one. */
    @Transactional
    public List<UUID> revokeAll(UUID userId) {
        List<UUID> revoked = sessions.revokeAllUserSessions(userId, REVOKED_BY_USER, clock.instant());
        log.info("User {} revoked all sessions {}", userId, revoked);
        return revoked;
    }

    /** Checked on every authenticated request, so revocation takes effect before the access token expires. */
    public boolean isActive(UUID sessionId, UUID userId) {
        return sessions.isActive(sessionId, userId, clock.instant());
    }

    private SessionTokens issueTokens(UUID userId, UUID sessionId, Instant sessionExpiresAt, Instant now) {
        String refreshToken = newRefreshToken();
        Instant refreshExpiresAt = min(now.plus(properties.refreshTokenTtl()), sessionExpiresAt);
        sessions.insertRefreshToken(UUID.randomUUID(), sessionId, sha256(refreshToken), now, refreshExpiresAt);
        AccessTokenIssuer.AccessToken access = accessTokens.issue(userId, sessionId);
        return new SessionTokens(userId, sessionId, access.value(), access.expiresAt(), refreshToken,
                refreshExpiresAt);
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Refresh tokens carry 256 bits of entropy, so an unkeyed hash is sufficient at rest. */
    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
