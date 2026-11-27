package org.myorg.dispatchpay.stock;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param reconciliationInterval delay between reconciliation runs */
@Validated
@ConfigurationProperties("dispatchpay.stock")
public record StockProperties(@NotNull Duration reconciliationInterval) {
}
