package org.myorg.dispatchpay.imports;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.myorg.dispatchpay.catalog.ProductRules;
import org.myorg.dispatchpay.catalog.ProductService;
import org.myorg.dispatchpay.catalog.ProductService.StockProduct;
import org.myorg.dispatchpay.catalog.Unit;
import org.myorg.dispatchpay.godown.GodownService;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.stock.StockKey;
import org.myorg.dispatchpay.stock.StockQuantity;
import org.myorg.dispatchpay.stock.StockQuantity.Converted;
import org.myorg.dispatchpay.stock.StockQuantity.Problem;
import org.myorg.dispatchpay.stock.StockService;
import org.myorg.dispatchpay.stock.StockService.ImportedOpening;
import org.springframework.stereotype.Component;

/**
 * Opening stock rows: {@code sku, godown, quantity} required, {@code unit} optional. The product
 * must exist and be active; the godown is matched by name (ignoring case) and must be in the
 * member's godown scope; the unit defaults to the product's base unit and otherwise needs a
 * conversion. Each product and godown pair may appear once, and only while it has no stock
 * movement: opening stock is the first stock of a product in a godown. Committing posts one
 * opening movement per row to the stock ledger.
 */
@Component
class OpeningStockRows implements RowRules {

    private static final int GODOWN_MAX = 120;

    /** Each value is {@code null} when its cell is blank or invalid, so the others are still checked. */
    private record Parsed(String sku, String godown, BigDecimal quantity, Unit unit) {
    }

    /** A row resolved against the business's products and godowns; {@code opening} only when valid. */
    private record Resolved(RowReader reader, ImportedOpening opening) {
    }

    private final ProductService products;
    private final GodownService godowns;
    private final StockService stock;

    OpeningStockRows(ProductService products, GodownService godowns, StockService stock) {
        this.products = products;
        this.godowns = godowns;
        this.stock = stock;
    }

    @Override
    public ImportType type() {
        return ImportType.OPENING_STOCK;
    }

    @Override
    public List<List<RowError>> validate(BusinessAccess access, List<Row> rows) {
        return resolve(access, rows).stream().map(row -> row.reader().errors()).toList();
    }

    @Override
    public List<UUID> commit(BusinessAccess access, UUID importId, List<Row> rows) {
        List<ImportedOpening> openings = new ArrayList<>();
        List<Resolved> resolved = resolve(access, rows);
        for (int i = 0; i < rows.size(); i++) {
            if (resolved.get(i).opening() == null) {
                throw new IllegalStateException("Row " + rows.get(i).number() + " is not valid");
            }
            openings.add(resolved.get(i).opening());
        }
        return stock.postImportedOpening(access.businessId(), access.membershipId(), importId, openings);
    }

    private List<Resolved> resolve(BusinessAccess access, List<Row> rows) {
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

        List<StockProduct> rowProducts = new ArrayList<>();
        List<UUID> rowGodowns = new ArrayList<>();
        List<StockQuantity> quantities = new ArrayList<>();
        Map<StockKey, Integer> firstRow = new HashMap<>();
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
            StockQuantity quantity = null;
            if (product != null && row.quantity() != null) {
                switch (StockQuantity.convert(product, row.quantity(), row.unit())) {
                    case Converted converted -> quantity = converted.quantity();
                    case Problem problem -> reader.error(problem.column(), problem.column().equals("unit")
                            ? RowError.UNIT_NOT_CONVERTIBLE : RowError.OUT_OF_RANGE, problem.message());
                }
            } else if (product != null && row.unit() != null && row.unit() != product.baseUnit()
                    && !product.conversions().containsKey(row.unit())) {
                reader.error("unit", RowError.UNIT_NOT_CONVERTIBLE, "Product " + product.sku() + " is counted in "
                        + product.baseUnit() + " and has no conversion for " + row.unit());
            }
            if (product != null && godownId != null) {
                Integer first = firstRow.putIfAbsent(new StockKey(godownId, product.id()), rows.get(i).number());
                if (first != null) {
                    reader.error("sku", RowError.DUPLICATE_IN_FILE, "Same product and godown as row " + first);
                }
            }
            rowProducts.add(product);
            rowGodowns.add(godownId);
            quantities.add(quantity);
        }

        Set<StockKey> stocked = stock.keysWithMovements(access.businessId(), firstRow.keySet());
        List<Resolved> resolved = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            RowReader reader = readers.get(i);
            StockProduct product = rowProducts.get(i);
            UUID godownId = rowGodowns.get(i);
            if (product != null && godownId != null && stocked.contains(new StockKey(godownId, product.id()))) {
                reader.error(null, RowError.STOCK_EXISTS, "Product " + product.sku() + " already has stock"
                        + " movements in this godown; opening stock can only be posted before any other movement");
            }
            ImportedOpening opening = reader.valid() && quantities.get(i) != null
                    ? new ImportedOpening(rows.get(i).number(), godownId, product, quantities.get(i))
                    : null;
            resolved.add(new Resolved(reader, opening));
        }
        return resolved;
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
