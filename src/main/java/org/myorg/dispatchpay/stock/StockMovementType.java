package org.myorg.dispatchpay.stock;

/**
 * Why stock moved. Stored in {@code stock_movement.type}: add a constant only together with the
 * migration that widens the check constraint.
 */
public enum StockMovementType {

    /** The first stock of a product in a godown, posted before any other movement of that pair. */
    OPENING,
    /** Goods received into a godown. */
    INWARD,
    /** A signed correction with a reason. */
    ADJUSTMENT
}
