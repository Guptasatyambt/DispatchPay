package org.myorg.dispatchpay.iam.auth;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.myorg.dispatchpay.platform.ratelimit.RateLimitRule;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("dispatchpay.auth")
public record AuthProperties(@Valid @NotNull Jwt jwt, @Valid @NotNull Session session, @Valid @NotNull Otp otp) {

    /**
     * @param secret HMAC-SHA256 signing key, at least 32 bytes; injected from the environment
     */
    public record Jwt(@NotBlank String issuer, @NotBlank @Size(min = 32) String secret,
            @NotNull Duration accessTokenTtl) {
    }

    /**
     * @param refreshTokenTtl lifetime of each rotated refresh token (idle timeout)
     * @param maxLifetime     absolute session lifetime; re-login required afterwards
     * @param refreshPerIp    refresh attempts allowed per client IP
     */
    public record Session(@NotNull Duration refreshTokenTtl, @NotNull Duration maxLifetime,
            @Valid @NotNull RateLimitRule refreshPerIp) {
    }

    /**
     * @param pepper         HMAC key for stored OTP hashes; injected from the environment
     * @param resendCooldown minimum gap between OTPs for one mobile
     */
    public record Otp(
            @Min(4) @Max(8) int length,
            @NotNull Duration ttl,
            @Min(1) int maxAttempts,
            @NotNull Duration resendCooldown,
            @NotBlank @Size(min = 32) String pepper,
            @Valid @NotNull Limits limits) {
    }

    public record Limits(
            @Valid @NotNull RateLimitRule requestPerMobile,
            @Valid @NotNull RateLimitRule requestPerMobileDaily,
            @Valid @NotNull RateLimitRule requestPerDevice,
            @Valid @NotNull RateLimitRule requestPerIp,
            @Valid @NotNull RateLimitRule verifyPerIp) {
    }
}
