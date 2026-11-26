package org.myorg.dispatchpay.imports;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * @param maxFileSize    largest CSV file accepted
 * @param maxRows        most data rows (the header not counted) in one file
 * @param uploadUrlTtl   lifetime of the pre-signed upload URL
 * @param downloadUrlTtl lifetime of an error report download link
 * @param pollInterval   delay between worker polls
 * @param lease          how long a worker owns a claimed import before another may take it over
 * @param maxAttempts    claims of one step before it is given up as failed
 */
@Validated
@ConfigurationProperties("dispatchpay.imports")
public record ImportProperties(
        @NotNull DataSize maxFileSize,
        @Min(1) int maxRows,
        @NotNull Duration uploadUrlTtl,
        @NotNull Duration downloadUrlTtl,
        @NotNull Duration pollInterval,
        @NotNull Duration lease,
        @Min(1) int maxAttempts) {
}
