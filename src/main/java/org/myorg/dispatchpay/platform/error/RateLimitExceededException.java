package org.myorg.dispatchpay.platform.error;

import java.time.Duration;

/** 429 with a {@code Retry-After} hint. */
public class RateLimitExceededException extends ApiException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super(ErrorCode.TOO_MANY_REQUESTS, "Too many requests; retry later");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
