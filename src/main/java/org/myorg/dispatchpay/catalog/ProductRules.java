package org.myorg.dispatchpay.catalog;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Field rules for products, shared by the API and imports so both accept exactly the same values.
 */
public final class ProductRules {

    public static final String SKU = "[A-Za-z0-9][A-Za-z0-9._/-]{0,63}";
    public static final String SKU_MESSAGE = "sku must be 1-64 letters, digits, '.', '_', '/' or '-'";
    public static final Pattern SKU_PATTERN = Pattern.compile(SKU);
    public static final int NAME_MAX = 200;
    public static final int BRAND_MAX = 120;
    public static final int PACK_SIZE_MAX = 60;
    public static final int ALIAS_MAX = 120;
    /** Upper bound for a price in paise (₹1,000 crore). */
    public static final long PRICE_MAX = 1_000_000_000_000L;
    /** Digits allowed before the decimal point of a conversion factor or quantity. */
    public static final int MAX_INTEGER_DIGITS = 13;
    /** Decimal places of a conversion factor or quantity. */
    public static final int SCALE = Unit.SCALE;

    private ProductRules() {
    }

    /** The unit named {@code raw} (case-insensitive), or {@code null} when there is none. */
    public static Unit unit(String raw) {
        try {
            return Unit.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Why {@code factor} cannot convert {@code unit} for a product counted in {@code base}, or
     * {@code null} when it is valid: positive, at most {@value #MAX_INTEGER_DIGITS} digits before and
     * {@value #SCALE} after the point, not the base unit itself, and the standard ratio between
     * units of the same dimension.
     */
    public static String conversionProblem(Unit base, Unit unit, BigDecimal factor) {
        if (unit == base) {
            return unit + " is the base unit and cannot have a conversion";
        }
        if (factor == null || factor.signum() <= 0) {
            return "The factor for " + unit + " must be greater than zero";
        }
        BigDecimal plain = plain(factor);
        if (!fitsDecimal(plain)) {
            return "The factor for " + unit + " must have at most " + MAX_INTEGER_DIGITS + " digits before and "
                    + SCALE + " after the decimal point";
        }
        BigDecimal standard = unit.standardFactorTo(base);
        if (standard != null && plain.compareTo(standard) != 0) {
            return "1 " + unit + " is always " + standard.toPlainString() + " " + base;
        }
        return null;
    }

    /** Whether {@code value} has at most {@value #MAX_INTEGER_DIGITS} integer and {@value #SCALE} decimal digits. */
    public static boolean fitsDecimal(BigDecimal value) {
        BigDecimal plain = plain(value);
        return plain.scale() <= SCALE && plain.precision() - plain.scale() <= MAX_INTEGER_DIGITS;
    }

    /** Without trailing zeros or exponent notation: 12, 0.5, 0.001. */
    public static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
