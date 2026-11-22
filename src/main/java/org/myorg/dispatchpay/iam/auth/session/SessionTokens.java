package org.myorg.dispatchpay.iam.auth.session;

import java.time.Instant;
import java.util.UUID;

public record SessionTokens(
        UUID userId,
        UUID sessionId,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt) {

    @Override
    public String toString() {
        return "SessionTokens[userId=" + userId + ", sessionId=" + sessionId + ", tokens=<redacted>]";
    }
}
