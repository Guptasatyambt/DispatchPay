package org.myorg.dispatchpay.stock;

/** Why stock was adjusted. A note is required with {@link #OTHER}. Stored in {@code stock_document.reason}. */
public enum AdjustmentReason {
    DAMAGE, LOSS, THEFT, EXPIRY, COUNT_CORRECTION, OTHER
}
