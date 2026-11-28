package org.myorg.dispatchpay.order;

/**
 * Runtime lifecycle of a sales order: {@code DRAFT → CONFIRMED → PARTIALLY_DISPATCHED → FULFILLED}.
 * Cancelling every remaining quantity closes an order: {@code CANCELLED} when nothing was
 * dispatched, otherwise {@code FULFILLED}.
 */
public enum OrderStatus {
    DRAFT, CONFIRMED, PARTIALLY_DISPATCHED, FULFILLED, CANCELLED;

    /** Confirmed and not closed: lines can still be dispatched or cancelled. */
    public boolean open() {
        return this == CONFIRMED || this == PARTIALLY_DISPATCHED;
    }
}
