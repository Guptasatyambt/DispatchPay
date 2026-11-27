package org.myorg.dispatchpay.stock;

import java.util.UUID;

/** A product in a godown: what a balance is kept for and what postings lock. */
public record StockKey(UUID godownId, UUID productId) {
}
