package org.myorg.dispatchpay.imports;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.myorg.dispatchpay.catalog.ProductRules;
import org.myorg.dispatchpay.catalog.ProductService;
import org.myorg.dispatchpay.catalog.ProductService.ImportedProduct;
import org.myorg.dispatchpay.catalog.ProductService.NewProduct;
import org.myorg.dispatchpay.catalog.Unit;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.springframework.stereotype.Component;

/**
 * Product rows: {@code sku, name, baseUnit} required; {@code brand, packSize, price} (paise) optional;
 * {@code aliases} as {@code lux|lux sabun} and {@code conversions} as {@code BOX=12|CARTON=144}.
 * Imports only create products: a SKU that already exists, or appears twice in the file, is an error.
 */
@Component
class ProductRows implements RowRules {

    private static final String CONVERSIONS_FORMAT = "conversions must look like BOX=12|CARTON=144";

    /**
     * @param sku     the row's SKU when that cell is valid, so duplicates are found whatever else is wrong
     * @param product the whole row, only when every cell is valid
     */
    private record Parsed(String sku, ImportedProduct product) {
    }

    private final ProductService products;

    ProductRows(ProductService products) {
        this.products = products;
    }

    @Override
    public ImportType type() {
        return ImportType.PRODUCTS;
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

        List<String> skus = parsed.stream().map(Parsed::sku).filter(Objects::nonNull).toList();
        Set<String> taken = products.takenSkus(access.businessId(), skus);
        Map<String, Integer> firstRow = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            if (parsed.get(i).sku() == null) {
                continue;
            }
            String sku = parsed.get(i).sku().toLowerCase(Locale.ROOT);
            Integer first = firstRow.putIfAbsent(sku, rows.get(i).number());
            if (first != null) {
                readers.get(i).error("sku", RowError.DUPLICATE_IN_FILE, "Same SKU as row " + first);
            } else if (taken.contains(sku)) {
                readers.get(i).error("sku", RowError.ALREADY_EXISTS, "A product with this SKU already exists");
            }
        }
        return readers.stream().map(RowReader::errors).toList();
    }

    @Override
    public List<UUID> commit(BusinessAccess access, List<Row> rows) {
        List<UUID> ids = new ArrayList<>();
        for (Row row : rows) {
            ImportedProduct product = parse(new RowReader(row.values())).product();
            if (product == null) {
                throw new IllegalStateException("Row " + row.number() + " is not valid");
            }
            ids.add(products.createImported(access.businessId(), access.membershipId(), product));
        }
        return ids;
    }

    /** What a row describes; invalid cells are recorded on the reader. */
    private static Parsed parse(RowReader row) {
        String sku = row.required("sku", 64);
        if (sku != null && !ProductRules.SKU_PATTERN.matcher(sku).matches()) {
            row.error("sku", RowError.INVALID_FORMAT, ProductRules.SKU_MESSAGE);
            sku = null;
        }
        String name = row.required("name", ProductRules.NAME_MAX);
        Unit baseUnit = null;
        String rawUnit = row.required("baseUnit", 16);
        if (rawUnit != null) {
            baseUnit = ProductRules.unit(rawUnit);
            if (baseUnit == null) {
                row.error("baseUnit", RowError.INVALID_UNIT, "baseUnit must be one of " + List.of(Unit.values()));
            }
        }
        String brand = row.optional("brand", ProductRules.BRAND_MAX);
        String packSize = row.optional("packSize", ProductRules.PACK_SIZE_MAX);
        Long price = row.wholeNumber("price", 0, ProductRules.PRICE_MAX);
        List<String> aliases = row.aliases("aliases", ProductRules.ALIAS_MAX);
        Map<Unit, BigDecimal> conversions = conversions(row, baseUnit);
        if (!row.valid()) {
            return new Parsed(sku, null);
        }
        return new Parsed(sku,
                new ImportedProduct(new NewProduct(sku, name, baseUnit, brand, packSize, price), aliases, conversions));
    }

    private static Map<Unit, BigDecimal> conversions(RowReader row, Unit baseUnit) {
        String value = row.text("conversions");
        Map<Unit, BigDecimal> conversions = new LinkedHashMap<>();
        if (value == null) {
            return conversions;
        }
        for (String part : RowReader.split(value)) {
            int equals = part.indexOf('=');
            if (equals < 0) {
                row.error("conversions", RowError.INVALID_FORMAT, CONVERSIONS_FORMAT);
                continue;
            }
            String rawUnit = part.substring(0, equals).strip();
            Unit unit = ProductRules.unit(rawUnit);
            if (unit == null) {
                row.error("conversions", RowError.INVALID_UNIT,
                        "\"" + rawUnit + "\" is not a unit; units are " + List.of(Unit.values()));
                continue;
            }
            BigDecimal factor = row.decimal("conversions", part.substring(equals + 1).strip());
            if (factor == null) {
                continue;
            }
            if (conversions.containsKey(unit)) {
                row.error("conversions", RowError.INVALID_CONVERSION, unit + " is listed more than once");
                continue;
            }
            String problem = baseUnit == null ? null : ProductRules.conversionProblem(baseUnit, unit, factor);
            if (problem != null) {
                row.error("conversions", RowError.INVALID_CONVERSION, problem);
                continue;
            }
            conversions.put(unit, ProductRules.plain(factor));
        }
        return conversions;
    }
}
