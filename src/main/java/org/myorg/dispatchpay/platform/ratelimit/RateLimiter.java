package org.myorg.dispatchpay.platform.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import org.myorg.dispatchpay.platform.error.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed-window rate limiter backed by PostgreSQL, consistent across application instances.
 * Hits are counted in their own transaction so a rolled-back request still consumes quota.
 * Identifiers (mobile, IP, device) are stored only as SHA-256 hashes.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    public RateLimiter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Records one hit for {@code identifier} under {@code scope}.
     *
     * @throws RateLimitExceededException if the hit exceeds the rule's limit for the current window
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(String scope, String identifier, RateLimitRule rule) {
        Instant now = clock.instant();
        long windowMillis = rule.window().toMillis();
        Instant windowStart = Instant.ofEpochMilli(now.toEpochMilli() - Math.floorMod(now.toEpochMilli(), windowMillis));
        Instant windowEnd = windowStart.plus(rule.window());

        int hits = jdbc.sql("""
                        INSERT INTO rate_limit_bucket (bucket_key, window_start, hits, expires_at)
                        VALUES (:key, :windowStart, 1, :expiresAt)
                        ON CONFLICT (bucket_key, window_start) DO UPDATE SET hits = rate_limit_bucket.hits + 1
                        RETURNING hits
                        """)
                .param("key", scope + ":" + hash(identifier))
                .param("windowStart", Timestamp.from(windowStart))
                .param("expiresAt", Timestamp.from(windowEnd))
                .query(Integer.class)
                .single();

        if (hits > rule.limit()) {
            throw new RateLimitExceededException(Duration.between(now, windowEnd));
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    @Transactional
    public void purgeExpired() {
        int deleted = jdbc.sql("DELETE FROM rate_limit_bucket WHERE expires_at <= :now")
                .param("now", Timestamp.from(clock.instant()))
                .update();
        if (deleted > 0) {
            log.debug("Purged {} expired rate-limit buckets", deleted);
        }
    }

    private static String hash(String identifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identifier.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
