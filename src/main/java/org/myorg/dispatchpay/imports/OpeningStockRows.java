package org.myorg.dispatchpay.imports;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.myorg.dispatchpay.catalog.ProductRules;
import org.myorg.dispatchpay.catalog.ProductService;
import org.myorg.dispatchpay.catalog.ProductService.StockProduct;
import org.myorg.dispatchpay.catalog.Unit;
import org.myorg.dispatchpay.godown.GodownService;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.springframework.stereotype.Component;

/**
 * Opening stock rows: {@code sku, godown, quantity} required, {@code unit} optional. The product
 * must exist and be active; the godown is matched by name (ignoring case) and must be in the
 * member's godown scope; the unit defaults to the product's base unit and otherwise needs a
 * conversion. Each product and godown pair may appear once.
 *
 * <p>Committing needs the stock ledger, which does not exist yet, so these imports stop at
 * validation; {@link #commit} is never reached.
 */
@Component
class OpeningStockRows implements RowRules {

    private static final int GODOWN_MAX = 120;

    /** Each value is {@code null} when its cell is blank or invalid, so the others are still checked. */
    private record Parsed(String sku, String godown, BigDecimal quantity, Unit unit) {
    }

    private final ProductService products;
    private final GodownService godowns;

    OpeningStockRows(ProductService products, GodownService godowns) {
        this.products = products;
        this.godowns = godowns;
    }

    @Override
    public ImportType type() {
        return ImportType.OPENING_STOCK;
    }

    @Override
    public List<List<RowError>> validate(BusinessAccess access, List<Row> rows) {
        List<RowReader> readers = new ArrayList<>();
        List<Parsed> parsed = new ArrayList<>();
        for (Row row : rows) {
            RowReader reader = new RowReader(row.values());
            readers.add(reader);
            parsed.add(parse(reader));
        }
        Map<String, StockProduct> productsBySku = products.findBySkus(access.businessId(),
                parsed.stream().map(Parsed::sku).filter(Objects::nonNull).toList());
        Map<String, UUID> godownsByName = godowns.idsByName(access.businessId(),
                parsed.stream().map(Parsed::godown).filter(Objects::nonNull).toList());

        Map<String, Integer> firstRow = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Parsed row = parsed.get(i);
            RowReader reader = readers.get(i);
            StockProduct product = row.sku() == null ? null : productsBySku.get(row.sku().toLowerCase(Locale.ROOT));
            UUID godownId = row.godown() == null ? null : godownsByName.get(row.godown().toLowerCase(Locale.ROOT));
            if (row.sku() != null && product == null) {
                reader.error("sku", RowError.UNKNOWN_PRODUCT, "No product has this SKU");
            } else if (product != null && product.status() != ProductService.Status.ACTIVE) {
                reader.error("sku", RowError.PRODUCT_INACTIVE, "Product " + product.sku() + " is inactive");
            }
            if (row.godown() != null && godownId == null) {
                reader.error("godown", RowError.UNKNOWN_GODOWN, "No godown has this name");
            } else if (godownId != null && !access.godownScope().includes(godownId)) {
                reader.error("godown", RowError.GODOWN_NOT_IN_SCOPE, "You do not have access to this godown");
            }
            if (product != null && row.unit() != null && row.unit() != product.baseUnit()
                    && !product.conversions().containsKey(row.unit())) {
                reader.error("unit", RowError.UNIT_NOT_CONVERTIBLE, "Product " + product.sku() + " is counted in "
                        + product.baseUnit() + " and has no conversion for " + row.unit());
            }
            if (product != null && godownId != null) {
                Integer first = firstRow.putIfAbsent(product.id() + "/" + godownId, rows.get(i).number());
                if (first != null) {
                    reader.error("sku", RowError.DUPLICATE_IN_FILE, "Same product and godown as row " + first);
                }
            }
        }
        return readers.stream().map(RowReader::errors).toList();
    }

    @Override
    public List<UUID> commit(BusinessAccess access, List<Row> rows) {
        throw new UnsupportedOperationException("Opening stock cannot be committed before the stock ledger exists");
    }

    private static Parsed parse(RowReader row) {
        String sku = row.required("sku", 64);
        String godown = row.required("godown", GODOWN_MAX);
        BigDecimal quantity = null;
        String rawQuantity = row.required("quantity", 40);
        if (rawQuantity != null) {
            quantity = row.decimal("quantity", rawQuantity);
            if (quantity != null && quantity.signum() <= 0) {
                row.error("quantity", RowError.OUT_OF_RANGE, "quantity must be greater than zero");
                quantity = null;
            } else if (quantity != null && !ProductRules.fitsDecimal(quantity)) {
                row.error("quantity", RowError.OUT_OF_RANGE, "quantity must have at most "
                        + ProductRules.MAX_INTEGER_DIGITS + " digits before and " + ProductRules.SCALE
                        + " after the decimal point");
                quantity = null;
            }
        }
        Unit unit = null;
        String rawUnit = row.optional("unit", 16);
        if (rawUnit != null) {
            unit = ProductRules.unit(rawUnit);
            if (unit == null) {
                row.error("unit", RowError.INVALID_UNIT, "unit must be one of " + List.of(Unit.values()));
            }
        }
        return new Parsed(sku, godown, quantity, unit);
    }
}
