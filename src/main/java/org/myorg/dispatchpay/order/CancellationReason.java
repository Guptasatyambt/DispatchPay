package org.myorg.dispatchpay.order;

/** Why order quantities were cancelled; {@code OTHER} needs a note. */
public enum CancellationReason {
    CUSTOMER_REQUEST, OUT_OF_STOCK, DUPLICATE, PRICING, OTHER
}
