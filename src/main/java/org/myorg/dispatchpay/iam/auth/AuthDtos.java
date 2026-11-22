package org.myorg.dispatchpay.iam.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.myorg.dispatchpay.iam.auth.session.SessionTokens;

/** Request and response bodies for /v1/auth. */
public final class AuthDtos {

    private static final String DEVICE_ID_PATTERN = "[A-Za-z0-9._:-]{8,128}";

    private AuthDtos() {
    }

    public record OtpRequest(
            @NotBlank String mobile,
            @Pattern(regexp = DEVICE_ID_PATTERN, message = "must be 8-128 letters, digits, '.', '_', ':' or '-'")
            String deviceId) {
    }

    /** Identical for every well-formed mobile; it never says whether an account exists. */
    public record OtpRequestAccepted(String message, long expiresInSeconds, long resendAfterSeconds) {
    }

    public record OtpVerifyRequest(
            @NotBlank String mobile,
            @NotBlank @Pattern(regexp = "\\d{4,8}", message = "must be 4-8 digits") String code,
            @Pattern(regexp = DEVICE_ID_PATTERN, message = "must be 8-128 letters, digits, '.', '_', ':' or '-'")
            String deviceId,
            @Size(max = 100) String deviceName) {

        @Override
        public String toString() {
            return "OtpVerifyRequest[code=<redacted>]";
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=<redacted>]";
        }
    }

    public record TokenResponse(
            String tokenType,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            UUID userId,
            UUID sessionId) {

        static TokenResponse from(SessionTokens tokens) {
            return new TokenResponse("Bearer", tokens.accessToken(), tokens.accessTokenExpiresAt(),
                    tokens.refreshToken(), tokens.refreshTokenExpiresAt(), tokens.userId(), tokens.sessionId());
        }

        @Override
        public String toString() {
            return "TokenResponse[userId=" + userId + ", sessionId=" + sessionId + ", tokens=<redacted>]";
        }
    }
}
