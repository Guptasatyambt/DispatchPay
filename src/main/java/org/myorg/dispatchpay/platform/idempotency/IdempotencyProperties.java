package org.myorg.dispatchpay.platform.idempotency;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param retention       how long a completed key replays its original response
 * @param cleanupInterval how often expired keys are purged
 */
@Validated
@ConfigurationProperties("dispatchpay.idempotency")
public record IdempotencyProperties(@NotNull Duration retention, @NotNull Duration cleanupInterval) {
}
