package org.myorg.dispatchpay.iam.auth.session;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service sign-out. A user can only revoke their own sessions. Revocation is naturally
 * idempotent, so no {@code Idempotency-Key} is required: repeating a call revokes nothing new.
 */
@RestController
@RequestMapping("/v1/sessions")
public class SessionController {

    public enum RevokeScope {
        /** The session of the calling access token. */
        CURRENT,
        /** The session named by {@code sessionId}. */
        SESSION,
        /** Every session of the user, including the calling one. */
        ALL
    }

    public record RevokeRequest(@NotNull RevokeScope scope, UUID sessionId) {

        @AssertTrue(message = "sessionId is required for scope SESSION and not allowed otherwise")
        public boolean isSessionIdMatchingScope() {
            return scope == null || (scope == RevokeScope.SESSION) == (sessionId != null);
        }
    }

    public record RevokeResponse(List<UUID> revokedSessionIds) {
    }

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/revoke")
    RevokeResponse revoke(@Valid @RequestBody RevokeRequest body, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<UUID> revoked = switch (body.scope()) {
            case CURRENT -> sessionService.revoke(userId,
                    UUID.fromString(jwt.getClaimAsString(AccessTokenIssuer.SESSION_CLAIM)));
            case SESSION -> sessionService.revoke(userId, body.sessionId());
            case ALL -> sessionService.revokeAll(userId);
        };
        return new RevokeResponse(revoked);
    }
}
