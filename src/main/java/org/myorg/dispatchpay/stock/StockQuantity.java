package org.myorg.dispatchpay.stock;

import java.math.BigDecimal;

import org.myorg.dispatchpay.catalog.ProductRules;
import org.myorg.dispatchpay.catalog.ProductService.StockProduct;
import org.myorg.dispatchpay.catalog.Unit;

/**
 * A quantity as entered, in any unit the product counts in, and the same quantity in the product's
 * base unit, which is what the ledger records. Shared by the API and imports so both accept exactly
 * the same quantities.
 *
 * @param factor how many base units one {@code unit} held when the quantity was entered
 */
public record StockQuantity(BigDecimal entered, Unit unit, BigDecimal factor, BigDecimal base) {

    /** The result of {@link #convert}: a quantity, or why the entered one cannot be used. */
    public sealed interface Conversion {
    }

    public record Converted(StockQuantity quantity) implements Conversion {
    }

    /** @param column the field the problem is with: {@code quantity} or {@code unit} */
    public record Problem(String column, String message) implements Conversion {
    }

    /**
     * Converts {@code entered} {@code unit}s (the base unit when {@code null}) of {@code product} to
     * its base unit. The unit must be the base unit or have a conversion; both the entered and the
     * converted quantity must have at most {@value ProductRules#MAX_INTEGER_DIGITS} digits before and
     * {@value ProductRules#SCALE} after the point. A conversion that would need rounding is refused
     * rather than rounded, so the ledger never records a quantity nobody entered.
     */
    public static Conversion convert(StockProduct product, BigDecimal entered, Unit unit) {
        Unit used = unit == null ? product.baseUnit() : unit;
        if (!ProductRules.fitsDecimal(entered)) {
            return new Problem("quantity", "quantity must have at most " + ProductRules.MAX_INTEGER_DIGITS
                    + " digits before and " + ProductRules.SCALE + " after the decimal point");
        }
        BigDecimal factor = used == product.baseUnit() ? BigDecimal.ONE : product.conversions().get(used);
        if (factor == null) {
            return new Problem("unit", "Product " + product.sku() + " is counted in " + product.baseUnit()
                    + " and has no conversion for " + used);
        }
        BigDecimal base = ProductRules.plain(entered.multiply(factor));
        if (!ProductRules.fitsDecimal(base)) {
            return new Problem("quantity", ProductRules.plain(entered).toPlainString() + " " + used + " is "
                    + base.toPlainString() + " " + product.baseUnit() + ", which has more than "
                    + ProductRules.MAX_INTEGER_DIGITS + " digits before or " + ProductRules.SCALE
                    + " after the decimal point");
        }
        return new Converted(new StockQuantity(ProductRules.plain(entered), used, ProductRules.plain(factor), base));
    }
}
