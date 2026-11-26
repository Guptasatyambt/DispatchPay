package org.myorg.dispatchpay.catalog;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.myorg.dispatchpay.audit.AuditChange;
import org.myorg.dispatchpay.audit.AuditEvent;
import org.myorg.dispatchpay.audit.AuditSource;
import org.myorg.dispatchpay.audit.AuditWriter;
import org.myorg.dispatchpay.catalog.ProductRepository.ConversionRow;
import org.myorg.dispatchpay.catalog.ProductRepository.Fields;
import org.myorg.dispatchpay.catalog.ProductRepository.ProductRow;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.Permission;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.myorg.dispatchpay.platform.search.SearchText;
import org.myorg.dispatchpay.platform.web.PatchRequest.Change;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business's product catalog. Reading needs {@link Permission#PRODUCT_READ}; every change needs
 * {@link Permission#PRODUCT_MANAGE}, is versioned and audited.
 *
 * <p>History stays stable: products are deactivated, never deleted, and a product's base unit never
 * changes. Documents that reference a product should still copy the SKU, name and unit they used.
 */
@Service
public class ProductService {

    private static final String RESOURCE = "product";

    public enum Status {
        ACTIVE, INACTIVE
    }

    /**
     * A search candidate. {@code matchedAliases} shows which approved aliases matched the query;
     * {@code price} is per base unit in paise.
     */
    public record ProductSummary(
            UUID id,
            String sku,
            String name,
            String baseUnit,
            String status,
            String brand,
            String packSize,
            Long price,
            List<String> matchedAliases) {
    }

    public record AliasView(UUID id, String alias, UUID approvedByMembershipId, Instant approvedAt) {
    }

    /** @param factor how many base units one {@code unit} holds */
    public record ConversionView(String unit, BigDecimal factor) {
    }

    /** @param price selling price of one base unit in paise; {@code null} when not set */
    public record ProductDetail(
            UUID id,
            String sku,
            String name,
            String baseUnit,
            String status,
            String brand,
            String packSize,
            Long price,
            List<AliasView> aliases,
            List<ConversionView> conversions,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    /** A product to create; brand, pack size and price are optional. */
    public record NewProduct(String sku, String name, Unit baseUnit, String brand, String packSize, Long price) {
    }

    /**
     * Fields to change; a {@code null} field is left as it is. For brand, pack size and price a
     * {@link Change} with a null value clears the field.
     */
    public record ProductChanges(
            String sku,
            String name,
            Status status,
            Unit baseUnit,
            Change<String> brand,
            Change<String> packSize,
            Change<Long> price) {
    }

    private final ProductRepository products;
    private final AuditWriter audit;
    private final Clock clock;

    public ProductService(ProductRepository products, AuditWriter audit, Clock clock) {
        this.products = products;
        this.audit = audit;
        this.clock = clock;
    }

    // --- M03.S02 search ---------------------------------------------------------------------------

    /**
     * Products matching {@code query} by SKU, name or approved alias, exact matches first. Every
     * matching product is returned: an alias shared by several products yields all of them as
     * candidates, never a silent pick. Inactive products are left out unless asked for.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductSummary> search(BusinessAccess access, String query, boolean includeInactive,
            PageQuery page) {
        access.require(Permission.PRODUCT_READ);
        String normalized = query == null || query.isBlank() ? null : SearchText.normalize(query);
        String pattern = normalized == null ? null : SearchText.containsPattern(normalized);
        List<ProductRow> rows = products.search(access.businessId(), normalized, pattern, includeInactive,
                page.offset(), page.size());
        Map<UUID, List<String>> aliases = products.matchingAliases(access.businessId(),
                rows.stream().map(ProductRow::id).toList(), pattern);
        List<ProductSummary> items = rows.stream()
                .map(row -> new ProductSummary(row.id(), row.sku(), row.name(), row.baseUnit(), row.status(),
                        row.brand(), row.packSize(), row.price(), aliases.getOrDefault(row.id(), List.of())))
                .toList();
        return PageResponse.of(items, page, products.count(access.businessId(), pattern, includeInactive));
    }

    /** Any product of the business, active or not, so historical references still resolve. */
    @Transactional(readOnly = true)
    public ProductDetail get(BusinessAccess access, UUID id) {
        access.require(Permission.PRODUCT_READ);
        ProductRow row = products.find(access.businessId(), id).orElseThrow(ProductService::notFound);
        return detail(access.businessId(), row);
    }

    // --- M03.S03 create and edit ------------------------------------------------------------------

    /** Runs inside the idempotent command's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductDetail create(BusinessAccess access, NewProduct product) {
        access.require(Permission.PRODUCT_MANAGE);
        UUID id = UUID.randomUUID();
        requireUniqueSku(access.businessId(), product.sku(), id);
        Fields fields = new Fields(product.sku(), product.name(), Status.ACTIVE.name(), product.brand(),
                product.packSize(), product.price());
        try {
            products.insert(id, access.businessId(), product.baseUnit(), fields, clock.instant());
        } catch (DuplicateKeyException concurrentCreate) {
            throw skuConflict();
        }
        Map<String, AuditChange> changes = new LinkedHashMap<>();
        changes.put("baseUnit", new AuditChange(null, product.baseUnit().name()));
        changes.putAll(diff(new Fields(null, null, null, null, null, null), fields));
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "product.create", RESOURCE, id,
                changes));
        return reload(access.businessId(), id);
    }

    /**
     * Edits SKU, name, lifecycle status, brand, pack size or price of a product last read at
     * {@code expectedVersion}. The base unit cannot change. Runs inside the idempotent command's
     * transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductDetail update(BusinessAccess access, UUID id, ProductChanges requested, long expectedVersion) {
        access.require(Permission.PRODUCT_MANAGE);
        ProductRow product = lockForChange(access, id, expectedVersion);
        if (requested.baseUnit() != null && !requested.baseUnit().name().equals(product.baseUnit())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "baseUnit cannot be changed; create a new product instead");
        }
        Fields before = product.fields();
        Fields after = new Fields(
                requested.sku() == null ? before.sku() : requested.sku(),
                requested.name() == null ? before.name() : requested.name(),
                requested.status() == null ? before.status() : requested.status().name(),
                requested.brand() == null ? before.brand() : requested.brand().value(),
                requested.packSize() == null ? before.packSize() : requested.packSize().value(),
                requested.price() == null ? before.price() : requested.price().value());

        Map<String, AuditChange> changes = diff(before, after);
        if (changes.isEmpty()) {
            return reload(access.businessId(), id);
        }
        if (changes.containsKey("sku")) {
            requireUniqueSku(access.businessId(), after.sku(), id);
        }
        try {
            products.update(id, after, clock.instant());
        } catch (DuplicateKeyException concurrentSku) {
            throw skuConflict();
        }
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "product.update", RESOURCE, id,
                changes));
        return reload(access.businessId(), id);
    }

    // --- M03.S04 aliases and conversions ----------------------------------------------------------

    /**
     * Adds an alias, approved by the caller. The same alias may be approved for several products;
     * search then returns them all. Runs inside the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductDetail addAlias(BusinessAccess access, UUID id, String alias) {
        access.require(Permission.PRODUCT_MANAGE);
        products.lock(access.businessId(), id).orElseThrow(ProductService::notFound);
        String normalized = SearchText.normalize(alias);
        if (products.aliasExists(access.businessId(), id, normalized)) {
            throw new ApiException(ErrorCode.CONFLICT, "This product already has this alias");
        }
        UUID aliasId = UUID.randomUUID();
        products.insertAlias(aliasId, access.businessId(), id, alias, normalized, access.membershipId(),
                clock.instant());
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "product.alias.add", RESOURCE, id,
                Map.of("alias", new AuditChange(null, alias))));
        return reload(access.businessId(), id);
    }

    /**
     * Replaces the product's unit conversions with exactly {@code conversions} (unit to number of
     * base units). Factors must be positive with at most {@value Unit#SCALE} decimals; the base unit
     * cannot be converted to itself; and units of the base unit's dimension must use the standard
     * ratio (a KG-based product's G factor is 0.001). Runs inside the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductDetail replaceConversions(BusinessAccess access, UUID id, Map<Unit, BigDecimal> conversions,
            long expectedVersion) {
        access.require(Permission.PRODUCT_MANAGE);
        ProductRow product = lockForChange(access, id, expectedVersion);
        Unit base = Unit.valueOf(product.baseUnit());
        Map<Unit, BigDecimal> validated = new TreeMap<>();
        conversions.forEach((unit, factor) -> validated.put(unit, validFactor(base, unit, factor)));

        Map<String, String> before = new TreeMap<>();
        products.findConversions(access.businessId(), id)
                .forEach(row -> before.put(row.unit(), plain(row.factor()).toPlainString()));
        Map<String, String> after = new TreeMap<>();
        validated.forEach((unit, factor) -> after.put(unit.name(), factor.toPlainString()));
        if (before.equals(after)) {
            return reload(access.businessId(), id);
        }
        Instant now = clock.instant();
        products.replaceConversions(access.businessId(), id, validated, now);
        products.bumpVersion(id, now);
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "product.conversions.replace",
                RESOURCE, id, Map.of("conversions", new AuditChange(before, after))));
        return reload(access.businessId(), id);
    }

    // --- M04 imports ------------------------------------------------------------------------------

    /** A product as an import creates it, with its approved aliases and conversions. */
    public record ImportedProduct(NewProduct product, List<String> aliases, Map<Unit, BigDecimal> conversions) {
    }

    /** What stock rows need to know about a product: identity, lifecycle and the units it counts in. */
    public record StockProduct(UUID id, String sku, Status status, Unit baseUnit, Map<Unit, BigDecimal> conversions) {
    }

    /** Which of the SKUs are already used in the business, lower-cased. */
    @Transactional(readOnly = true)
    public Set<String> takenSkus(UUID businessId, Collection<String> skus) {
        return products.takenSkus(businessId, lowerCase(skus));
    }

    /** Products with the given SKUs, keyed by lower-cased SKU. */
    @Transactional(readOnly = true)
    public Map<String, StockProduct> findBySkus(UUID businessId, Collection<String> skus) {
        List<ProductRow> rows = products.findBySkus(businessId, lowerCase(skus));
        Map<UUID, List<ConversionRow>> conversions = products.findConversions(businessId,
                rows.stream().map(ProductRow::id).toList());
        Map<String, StockProduct> found = new HashMap<>();
        for (ProductRow row : rows) {
            Map<Unit, BigDecimal> units = new EnumMap<>(Unit.class);
            conversions.getOrDefault(row.id(), List.of())
                    .forEach(conversion -> units.put(Unit.valueOf(conversion.unit()), plain(conversion.factor())));
            found.put(row.sku().toLowerCase(Locale.ROOT), new StockProduct(row.id(), row.sku(),
                    Status.valueOf(row.status()), Unit.valueOf(row.baseUnit()), units));
        }
        return found;
    }

    /**
     * Creates an imported product, active, with its aliases approved by the member who committed the
     * import. The import validated it already; rules are checked again here. Audited as system work
     * initiated by that member. Runs inside the import's commit transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createImported(UUID businessId, UUID initiatorMembershipId, ImportedProduct imported) {
        NewProduct product = imported.product();
        Map<Unit, BigDecimal> conversions = new TreeMap<>();
        imported.conversions().forEach((unit, factor) ->
                conversions.put(unit, validFactor(product.baseUnit(), unit, factor)));
        UUID id = UUID.randomUUID();
        requireUniqueSku(businessId, product.sku(), id);
        Instant now = clock.instant();
        Fields fields = new Fields(product.sku(), product.name(), Status.ACTIVE.name(), product.brand(),
                product.packSize(), product.price());
        products.insert(id, businessId, product.baseUnit(), fields, now);
        List<String> aliases = new ArrayList<>();
        for (String alias : imported.aliases()) {
            products.insertAlias(UUID.randomUUID(), businessId, id, alias, SearchText.normalize(alias),
                    initiatorMembershipId, now);
            aliases.add(alias);
        }
        products.replaceConversions(businessId, id, conversions, now);

        Map<String, AuditChange> changes = new LinkedHashMap<>();
        changes.put("baseUnit", new AuditChange(null, product.baseUnit().name()));
        changes.putAll(diff(new Fields(null, null, null, null, null, null), fields));
        if (!aliases.isEmpty()) {
            changes.put("aliases", new AuditChange(null, aliases));
        }
        if (!conversions.isEmpty()) {
            Map<String, String> after = new TreeMap<>();
            conversions.forEach((unit, factor) -> after.put(unit.name(), factor.toPlainString()));
            changes.put("conversions", new AuditChange(null, after));
        }
        audit.append(AuditEvent.system(businessId, initiatorMembershipId, AuditSource.IMPORT, "product.create",
                RESOURCE, id, changes));
        return id;
    }

    private static List<String> lowerCase(Collection<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** The fields that differ, in a stable order, for the audit diff. */
    private static Map<String, AuditChange> diff(Fields before, Fields after) {
        Map<String, AuditChange> changes = new LinkedHashMap<>();
        put(changes, "sku", before.sku(), after.sku());
        put(changes, "name", before.name(), after.name());
        put(changes, "status", before.status(), after.status());
        put(changes, "brand", before.brand(), after.brand());
        put(changes, "packSize", before.packSize(), after.packSize());
        put(changes, "price", before.price(), after.price());
        return changes;
    }

    private static void put(Map<String, AuditChange> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.put(field, new AuditChange(before, after));
        }
    }

    private static BigDecimal validFactor(Unit base, Unit unit, BigDecimal raw) {
        String problem = ProductRules.conversionProblem(base, unit, raw);
        if (problem != null) {
            throw invalidConversion(problem);
        }
        return plain(raw);
    }

    private ProductRow lockForChange(BusinessAccess access, UUID id, long expectedVersion) {
        ProductRow product = products.lock(access.businessId(), id).orElseThrow(ProductService::notFound);
        if (product.version() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "This product was changed by another request; reload and retry");
        }
        return product;
    }

    private void requireUniqueSku(UUID businessId, String sku, UUID exceptId) {
        if (products.skuTaken(businessId, sku, exceptId)) {
            throw skuConflict();
        }
    }

    private ProductDetail reload(UUID businessId, UUID id) {
        return detail(businessId, products.find(businessId, id).orElseThrow(ProductService::notFound));
    }

    private ProductDetail detail(UUID businessId, ProductRow row) {
        List<AliasView> aliases = products.findAliases(businessId, row.id()).stream()
                .map(alias -> new AliasView(alias.id(), alias.alias(), alias.approvedByMembershipId(),
                        alias.approvedAt()))
                .toList();
        List<ConversionView> conversions = products.findConversions(businessId, row.id()).stream()
                .map(ProductService::view)
                .toList();
        return new ProductDetail(row.id(), row.sku(), row.name(), row.baseUnit(), row.status(), row.brand(),
                row.packSize(), row.price(), aliases, conversions, row.createdAt(), row.updatedAt(), row.version());
    }

    private static ConversionView view(ConversionRow row) {
        return new ConversionView(row.unit(), plain(row.factor()));
    }

    private static BigDecimal plain(BigDecimal value) {
        return ProductRules.plain(value);
    }

    private static ApiException invalidConversion(String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
    }

    private static ApiException skuConflict() {
        return new ApiException(ErrorCode.CONFLICT, "A product with this SKU already exists");
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Product not found");
    }
}
