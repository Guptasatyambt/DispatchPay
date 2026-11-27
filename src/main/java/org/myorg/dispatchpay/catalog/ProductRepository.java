package org.myorg.dispatchpay.catalog;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Products of one business with their aliases and conversions. Every query is filtered by the business. */
@Repository
class ProductRepository {

    record ProductRow(
            UUID id,
            String sku,
            String name,
            String baseUnit,
            String status,
            String brand,
            String packSize,
            Long price,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        Fields fields() {
            return new Fields(sku, name, status, brand, packSize, price);
        }
    }

    /** The editable columns of a product. */
    record Fields(String sku, String name, String status, String brand, String packSize, Long price) {
    }

    record AliasRow(UUID id, UUID productId, String alias, UUID approvedByMembershipId, Instant approvedAt) {
    }

    record ConversionRow(String unit, BigDecimal factor) {
    }

    private static final String SELECT = """
            SELECT p.id, p.sku, p.name, p.base_unit, p.status, p.brand, p.pack_size, p.price,
                   p.created_at, p.updated_at, p.version
            FROM product p
            """;

    /**
     * Search filter: {@code :pattern} is a LIKE pattern over the normalized query (null lists
     * everything); a product matches on SKU, name or any approved alias.
     */
    private static final String SEARCH_WHERE = """
             WHERE p.business_id = :businessId
               AND (:includeInactive OR p.status = 'ACTIVE')
               AND (CAST(:pattern AS VARCHAR) IS NULL
                    OR lower(p.sku) LIKE :pattern ESCAPE '\\'
                    OR regexp_replace(lower(p.name), '[[:space:]]+', ' ', 'g') LIKE :pattern ESCAPE '\\'
                    OR EXISTS (SELECT 1 FROM product_alias a
                               WHERE a.business_id = p.business_id AND a.product_id = p.id
                                 AND a.normalized_alias LIKE :pattern ESCAPE '\\'))
            """;

    private final JdbcClient jdbc;

    ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Exact SKU, name or alias matches first, then by name. */
    List<ProductRow> search(UUID businessId, String query, String pattern, boolean includeInactive, long offset,
            int limit) {
        return jdbc.sql(SELECT + SEARCH_WHERE + """
                         ORDER BY CASE WHEN lower(p.sku) = :query
                                         OR regexp_replace(lower(p.name), '[[:space:]]+', ' ', 'g') = :query
                                         OR EXISTS (SELECT 1 FROM product_alias a
                                                    WHERE a.business_id = p.business_id AND a.product_id = p.id
                                                      AND a.normalized_alias = :query)
                                       THEN 0 ELSE 1 END,
                                  lower(p.name), p.id
                         LIMIT :limit OFFSET :offset
                        """)
                .param("businessId", businessId)
                .param("includeInactive", includeInactive)
                .param("pattern", pattern)
                .param("query", query == null ? "" : query)
                .param("limit", limit)
                .param("offset", offset)
                .query(ProductRepository::map)
                .list();
    }

    long count(UUID businessId, String pattern, boolean includeInactive) {
        return jdbc.sql("SELECT count(*) FROM product p" + SEARCH_WHERE)
                .param("businessId", businessId)
                .param("includeInactive", includeInactive)
                .param("pattern", pattern)
                .query(Long.class)
                .single();
    }

    /** Aliases of the given products matching {@code pattern}, by product. */
    Map<UUID, List<String>> matchingAliases(UUID businessId, Collection<UUID> productIds, String pattern) {
        if (productIds.isEmpty() || pattern == null) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT product_id, alias FROM product_alias
                        WHERE business_id = :businessId AND product_id IN (:ids)
                          AND normalized_alias LIKE :pattern ESCAPE '\\'
                        ORDER BY normalized_alias
                        """)
                .param("businessId", businessId)
                .param("ids", productIds)
                .param("pattern", pattern)
                .query((rs, rowNum) -> Map.entry(rs.getObject("product_id", UUID.class), rs.getString("alias")))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    Optional<ProductRow> find(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE p.business_id = :businessId AND p.id = :id")
                .param("businessId", businessId)
                .param("id", id)
                .query(ProductRepository::map)
                .optional();
    }

    Optional<ProductRow> lock(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE p.business_id = :businessId AND p.id = :id FOR UPDATE")
                .param("businessId", businessId)
                .param("id", id)
                .query(ProductRepository::map)
                .optional();
    }

    boolean skuTaken(UUID businessId, String sku, UUID exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM product
                                       WHERE business_id = :businessId AND lower(sku) = lower(:sku) AND id <> :exceptId)
                        """)
                .param("businessId", businessId)
                .param("sku", sku)
                .param("exceptId", exceptId)
                .query(Boolean.class)
                .single();
    }

    /** Which of {@code lowerSkus} (lower-cased) are taken in the business, lower-cased. */
    Set<String> takenSkus(UUID businessId, Collection<String> lowerSkus) {
        if (lowerSkus.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("""
                        SELECT lower(sku) FROM product WHERE business_id = :businessId AND lower(sku) IN (:skus)
                        """)
                .param("businessId", businessId)
                .param("skus", lowerSkus)
                .query(String.class)
                .list());
    }

    /** Products whose lower-cased SKU is one of {@code lowerSkus}. */
    List<ProductRow> findBySkus(UUID businessId, Collection<String> lowerSkus) {
        if (lowerSkus.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE p.business_id = :businessId AND lower(p.sku) IN (:skus)")
                .param("businessId", businessId)
                .param("skus", lowerSkus)
                .query(ProductRepository::map)
                .list();
    }

    List<ProductRow> findByIds(UUID businessId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE p.business_id = :businessId AND p.id IN (:ids)")
                .param("businessId", businessId)
                .param("ids", ids)
                .query(ProductRepository::map)
                .list();
    }

    /** Conversions of the given products, by product. */
    Map<UUID, List<ConversionRow>> findConversions(UUID businessId, Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT product_id, unit, factor FROM product_unit_conversion
                        WHERE business_id = :businessId AND product_id IN (:ids)
                        ORDER BY unit
                        """)
                .param("businessId", businessId)
                .param("ids", productIds)
                .query((rs, rowNum) -> Map.entry(rs.getObject("product_id", UUID.class),
                        new ConversionRow(rs.getString("unit"), rs.getBigDecimal("factor"))))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    void insert(UUID id, UUID businessId, Unit baseUnit, Fields fields, Instant now) {
        bind(jdbc.sql("""
                        INSERT INTO product (id, business_id, sku, name, base_unit, status, brand, pack_size, price,
                                             created_at, updated_at)
                        VALUES (:id, :businessId, :sku, :name, :baseUnit, :status, :brand, :packSize, :price,
                                :now, :now)
                        """), fields)
                .param("id", id)
                .param("businessId", businessId)
                .param("baseUnit", baseUnit.name())
                .param("now", Timestamp.from(now))
                .update();
    }

    void update(UUID id, Fields fields, Instant now) {
        bind(jdbc.sql("""
                        UPDATE product SET sku = :sku, name = :name, status = :status, brand = :brand,
                                           pack_size = :packSize, price = :price,
                                           version = version + 1, updated_at = :now
                        WHERE id = :id
                        """), fields)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Fields fields) {
        return spec.param("sku", fields.sku())
                .param("name", fields.name())
                .param("status", fields.status())
                .param("brand", fields.brand())
                .param("packSize", fields.packSize())
                .param("price", fields.price(), Types.BIGINT);
    }

    void bumpVersion(UUID id, Instant now) {
        jdbc.sql("UPDATE product SET version = version + 1, updated_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    List<AliasRow> findAliases(UUID businessId, UUID productId) {
        return jdbc.sql("""
                        SELECT id, product_id, alias, approved_by_membership_id, approved_at FROM product_alias
                        WHERE business_id = :businessId AND product_id = :productId
                        ORDER BY normalized_alias
                        """)
                .param("businessId", businessId)
                .param("productId", productId)
                .query((rs, rowNum) -> new AliasRow(rs.getObject("id", UUID.class),
                        rs.getObject("product_id", UUID.class), rs.getString("alias"),
                        rs.getObject("approved_by_membership_id", UUID.class),
                        rs.getTimestamp("approved_at").toInstant()))
                .list();
    }

    boolean aliasExists(UUID businessId, UUID productId, String normalizedAlias) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM product_alias
                                       WHERE business_id = :businessId AND product_id = :productId
                                         AND normalized_alias = :normalized)
                        """)
                .param("businessId", businessId)
                .param("productId", productId)
                .param("normalized", normalizedAlias)
                .query(Boolean.class)
                .single();
    }

    void insertAlias(UUID id, UUID businessId, UUID productId, String alias, String normalizedAlias,
            UUID approvedBy, Instant now) {
        jdbc.sql("""
                        INSERT INTO product_alias
                            (id, business_id, product_id, alias, normalized_alias, approved_by_membership_id,
                             approved_at)
                        VALUES (:id, :businessId, :productId, :alias, :normalized, :approvedBy, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("productId", productId)
                .param("alias", alias)
                .param("normalized", normalizedAlias)
                .param("approvedBy", approvedBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    List<ConversionRow> findConversions(UUID businessId, UUID productId) {
        return jdbc.sql("""
                        SELECT unit, factor FROM product_unit_conversion
                        WHERE business_id = :businessId AND product_id = :productId
                        ORDER BY unit
                        """)
                .param("businessId", businessId)
                .param("productId", productId)
                .query((rs, rowNum) -> new ConversionRow(rs.getString("unit"), rs.getBigDecimal("factor")))
                .list();
    }

    void replaceConversions(UUID businessId, UUID productId, Map<Unit, BigDecimal> conversions, Instant now) {
        jdbc.sql("DELETE FROM product_unit_conversion WHERE business_id = :businessId AND product_id = :productId")
                .param("businessId", businessId)
                .param("productId", productId)
                .update();
        conversions.forEach((unit, factor) -> jdbc.sql("""
                        INSERT INTO product_unit_conversion (business_id, product_id, unit, factor, created_at)
                        VALUES (:businessId, :productId, :unit, :factor, :now)
                        """)
                .param("businessId", businessId)
                .param("productId", productId)
                .param("unit", unit.name())
                .param("factor", factor)
                .param("now", Timestamp.from(now))
                .update());
    }

    private static ProductRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ProductRow(
                rs.getObject("id", UUID.class),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("base_unit"),
                rs.getString("status"),
                rs.getString("brand"),
                rs.getString("pack_size"),
                rs.getObject("price", Long.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
