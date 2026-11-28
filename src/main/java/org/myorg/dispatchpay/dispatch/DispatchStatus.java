package org.myorg.dispatchpay.dispatch;

/** Runtime lifecycle of a dispatch: {@code DRAFT → CONFIRMED}; only a draft can be cancelled. */
public enum DispatchStatus {
    DRAFT, CONFIRMED, CANCELLED
}
