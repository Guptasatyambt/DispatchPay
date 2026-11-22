package org.myorg.dispatchpay.platform.outbox;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param pollInterval   delay between worker polls
 * @param batchSize      maximum events processed per poll
 * @param maxAttempts    failed deliveries before an event is parked as DEAD
 * @param initialBackoff retry delay after the first failure, doubled per attempt
 * @param maxBackoff     cap on the retry delay
 */
@Validated
@ConfigurationProperties("dispatchpay.outbox")
public record OutboxProperties(
        @NotNull Duration pollInterval,
        @Min(1) int batchSize,
        @Min(1) int maxAttempts,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff) {
}
