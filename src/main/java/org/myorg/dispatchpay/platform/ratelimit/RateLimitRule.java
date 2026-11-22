package org.myorg.dispatchpay.platform.ratelimit;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** At most {@code limit} hits per fixed {@code window}. */
public record RateLimitRule(@Min(1) int limit, @NotNull Duration window) {
}
