package org.myorg.dispatchpay.order;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Order arithmetic in integer paise, each step rounded half-up to whole paise: the amount of a line
 * is its unit price times the quantity, less its discount percentage; tax is charged on the
 * discounted amount. Amounts are computed from base quantities, so a partially cancelled line is
 * priced exactly like an order for the quantity that is left.
 */
final class OrderMoney {

    /** Largest amount of a line, or of an order: ₹10 trillion. */
    static final long AMOUNT_MAX = 1_000_000_000_000_000L;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX = BigDecimal.valueOf(AMOUNT_MAX);

    /** @param total {@code amount - discount + tax} */
    record LineAmounts(long amount, long discount, long tax, long total) {
    }

    private OrderMoney() {
    }

    /** The price of one {@code factor}-sized unit when one base unit costs {@code basePrice}. */
    static long unitPrice(long basePrice, BigDecimal factor) {
        return paise(BigDecimal.valueOf(basePrice).multiply(factor));
    }

    /**
     * Amounts of {@code baseQuantity} base units at {@code unitPrice} per unit of {@code factor} base units.
     *
     * @throws ArithmeticException when an amount exceeds {@link #AMOUNT_MAX}
     */
    static LineAmounts line(long unitPrice, BigDecimal baseQuantity, BigDecimal factor, BigDecimal discountPercent,
            BigDecimal taxRate) {
        BigDecimal gross = BigDecimal.valueOf(unitPrice).multiply(baseQuantity)
                .divide(factor, 0, RoundingMode.HALF_UP);
        long amount = paise(gross);
        long discount = paise(gross.multiply(discountPercent).divide(HUNDRED));
        long tax = paise(BigDecimal.valueOf(amount - discount).multiply(taxRate).divide(HUNDRED));
        return new LineAmounts(amount, discount, tax, Math.addExact(amount - discount, tax));
    }

    private static long paise(BigDecimal value) {
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(MAX) > 0) {
            throw new ArithmeticException("amount exceeds " + AMOUNT_MAX);
        }
        return rounded.longValueExact();
    }
}
