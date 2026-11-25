package org.myorg.dispatchpay.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Units a product can be counted, weighed or measured in. Units of a physical dimension have a
 * fixed size relative to the others of that dimension, so a conversion between them must use the
 * standard ratio. Packaging units (box, bag, ...) have no fixed size: their factor is specific to
 * each product.
 */
public enum Unit {

    PCS(Dimension.COUNT, "1"),
    PAIR(Dimension.COUNT, "2"),
    DOZEN(Dimension.COUNT, "12"),

    G(Dimension.MASS, "1"),
    KG(Dimension.MASS, "1000"),
    QUINTAL(Dimension.MASS, "100000"),
    TONNE(Dimension.MASS, "1000000"),

    ML(Dimension.VOLUME, "1"),
    L(Dimension.VOLUME, "1000"),

    M(Dimension.LENGTH, "1"),

    BOX(null, null),
    CARTON(null, null),
    PACK(null, null),
    BAG(null, null),
    BUNDLE(null, null),
    ROLL(null, null),
    BOTTLE(null, null),
    CAN(null, null),
    SET(null, null);

    /** Decimal places a conversion factor is stored with. */
    static final int SCALE = 6;

    enum Dimension {
        COUNT, MASS, VOLUME, LENGTH
    }

    private final Dimension dimension;
    private final BigDecimal size;

    Unit(Dimension dimension, String size) {
        this.dimension = dimension;
        this.size = size == null ? null : new BigDecimal(size);
    }

    /**
     * The number of {@code base} units in one of this unit when both are standard units of the same
     * dimension (e.g. KG to G is 1000), rounded to {@link #SCALE} places; {@code null} when the
     * factor is product-specific.
     */
    BigDecimal standardFactorTo(Unit base) {
        if (dimension == null || dimension != base.dimension) {
            return null;
        }
        return size.divide(base.size, SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
