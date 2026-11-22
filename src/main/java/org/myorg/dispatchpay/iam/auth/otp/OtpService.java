package org.myorg.dispatchpay.iam.auth.otp;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.myorg.dispatchpay.iam.auth.AuthProperties;
import org.myorg.dispatchpay.iam.auth.MobileNumber;
import org.myorg.dispatchpay.platform.error.RateLimitExceededException;
import org.myorg.dispatchpay.platform.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues and checks one-time codes. Requests never consult user accounts, so the response and
 * work done are identical for registered and unknown numbers (no user enumeration).
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    public record OtpIssued(Duration validity, Duration resendAfter) {
    }

    private final OtpChallengeRepository challenges;
    private final OtpSender sender;
    private final RateLimiter rateLimiter;
    private final AuthProperties.Otp properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OtpService(OtpChallengeRepository challenges, OtpSender sender, RateLimiter rateLimiter,
            AuthProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.challenges = challenges;
        this.sender = sender;
        this.rateLimiter = rateLimiter;
        this.properties = properties.otp();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Applies abuse limits, replaces any previous code for the number and sends a new one.
     * Delivery failures are logged but not reported, so the response stays generic.
     */
    public OtpIssued request(MobileNumber mobile, String deviceId, String clientIp) {
        AuthProperties.Limits limits = properties.limits();
        rateLimiter.consume("otp-request:ip", clientIp, limits.requestPerIp());
        if (deviceId != null) {
            rateLimiter.consume("otp-request:device", deviceId, limits.requestPerDevice());
        }
        rateLimiter.consume("otp-request:mobile", mobile.e164(), limits.requestPerMobile());
        rateLimiter.consume("otp-request:mobile-daily", mobile.e164(), limits.requestPerMobileDaily());

        String code = generateCode();
        try {
            transactionTemplate.executeWithoutResult(status -> replaceChallenge(mobile, deviceId, code));
        } catch (DuplicateKeyException concurrentRequest) {
            throw new RateLimitExceededException(properties.resendCooldown());
        }

        try {
            sender.send(mobile, code, properties.ttl());
        } catch (RuntimeException e) {
            log.error("OTP delivery to {} failed", mobile.masked(), e);
        }
        return new OtpIssued(properties.ttl(), properties.resendCooldown());
    }

    /**
     * Checks a code against the active challenge, consuming it on success. Failed attempts are
     * counted and lock the challenge at the limit. Must run inside the caller's transaction,
     * which must commit even when this returns {@code false}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean verify(MobileNumber mobile, String code) {
        Instant now = clock.instant();
        Optional<OtpChallengeRepository.ActiveChallenge> active = challenges.lockActive(mobile.e164());
        if (active.isEmpty() || !active.get().expiresAt().isAfter(now)) {
            return false;
        }
        OtpChallengeRepository.ActiveChallenge challenge = active.get();
        boolean matches = MessageDigest.isEqual(
                hash(challenge.id(), code).getBytes(StandardCharsets.US_ASCII),
                challenge.codeHash().getBytes(StandardCharsets.US_ASCII));
        if (matches) {
            challenges.close(challenge.id(), "CONSUMED", now);
            return true;
        }
        int attempts = challenge.attempts() + 1;
        boolean lock = attempts >= challenge.maxAttempts();
        challenges.recordFailedAttempt(challenge.id(), attempts, lock, now);
        if (lock) {
            log.warn("OTP challenge for {} locked after {} failed attempts", mobile.masked(), attempts);
        }
        return false;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    @Transactional
    public void purgeOldChallenges() {
        challenges.deleteCreatedBefore(clock.instant().minus(Duration.ofDays(1)));
    }

    private void replaceChallenge(MobileNumber mobile, String deviceId, String code) {
        Instant now = clock.instant();
        Optional<OtpChallengeRepository.ActiveChallenge> previous = challenges.lockActive(mobile.e164());
        if (previous.isPresent()) {
            Instant resendAt = previous.get().createdAt().plus(properties.resendCooldown());
            if (resendAt.isAfter(now)) {
                throw new RateLimitExceededException(Duration.between(now, resendAt));
            }
            challenges.close(previous.get().id(), "SUPERSEDED", now);
        }
        UUID id = UUID.randomUUID();
        challenges.insert(id, mobile.e164(), hash(id, code), deviceId, properties.maxAttempts(),
                now, now.plus(properties.ttl()));
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, properties.length());
        return String.format("%0" + properties.length() + "d", random.nextInt(bound));
    }

    /** Keyed with the pepper, so leaked table rows alone cannot be brute-forced offline. */
    private String hash(UUID challengeId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.pepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((challengeId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
