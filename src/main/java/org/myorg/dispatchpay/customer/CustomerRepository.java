package org.myorg.dispatchpay.customer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Customers of one business with their aliases. Every query is filtered by the business. */
@Repository
class CustomerRepository {

    /** The editable columns of a customer. */
    record Fields(
            String username,
            String name,
            String contactPerson,
            String phone,
            String address,
            Integer paymentTermsDays,
            Long creditLimit) {
    }

    record CustomerRow(
            UUID id,
            Fields fields,
            UUID createdByMembershipId,
            UUID updatedByMembershipId,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    record AliasRow(UUID id, String alias, UUID approvedByMembershipId, Instant approvedAt) {
    }

    private static final String SELECT = """
            SELECT c.id, c.username, c.name, c.contact_person, c.phone, c.address, c.payment_terms_days, c.credit_limit,
                   c.created_by_membership_id, c.updated_by_membership_id, c.created_at, c.updated_at, c.version
            FROM customer c
            """;

    /**
     * Search filter: {@code :pattern} is a LIKE pattern over the normalized query (null lists
     * everything); a customer matches on username, name, phone or any approved alias.
     */
    private static final String SEARCH_WHERE = """
             WHERE c.business_id = :businessId
               AND (CAST(:pattern AS VARCHAR) IS NULL
                    OR regexp_replace(lower(c.name), '[[:space:]]+', ' ', 'g') LIKE :pattern ESCAPE '\\'
                    OR c.username LIKE :pattern ESCAPE '\\'
                    OR c.phone LIKE :pattern ESCAPE '\\'
                    OR EXISTS (SELECT 1 FROM customer_alias a
                               WHERE a.business_id = c.business_id AND a.customer_id = c.id
                                 AND a.normalized_alias LIKE :pattern ESCAPE '\\'))
            """;

    private final JdbcClient jdbc;

    CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Exact username, name or alias matches first, then by name. */
    List<CustomerRow> search(UUID businessId, String query, String pattern, long offset, int limit) {
        return jdbc.sql(SELECT + SEARCH_WHERE + """
                         ORDER BY CASE WHEN c.username = :query
                                         OR regexp_replace(lower(c.name), '[[:space:]]+', ' ', 'g') = :query
                                         OR EXISTS (SELECT 1 FROM customer_alias a
                                                    WHERE a.business_id = c.business_id AND a.customer_id = c.id
                                                      AND a.normalized_alias = :query)
                                       THEN 0 ELSE 1 END,
                                  lower(c.name), c.id
                         LIMIT :limit OFFSET :offset
                        """)
                .param("businessId", businessId)
                .param("pattern", pattern)
                .param("query", query == null ? "" : query)
                .param("limit", limit)
                .param("offset", offset)
                .query(CustomerRepository::map)
                .list();
    }

    long count(UUID businessId, String pattern) {
        return jdbc.sql("SELECT count(*) FROM customer c" + SEARCH_WHERE)
                .param("businessId", businessId)
                .param("pattern", pattern)
                .query(Long.class)
                .single();
    }

    /** Aliases of the given customers matching {@code pattern}, by customer. */
    Map<UUID, List<String>> matchingAliases(UUID businessId, Collection<UUID> customerIds, String pattern) {
        if (customerIds.isEmpty() || pattern == null) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT customer_id, alias FROM customer_alias
                        WHERE business_id = :businessId AND customer_id IN (:ids)
                          AND normalized_alias LIKE :pattern ESCAPE '\\'
                        ORDER BY normalized_alias
                        """)
                .param("businessId", businessId)
                .param("ids", customerIds)
                .param("pattern", pattern)
                .query((rs, rowNum) -> Map.entry(rs.getObject("customer_id", UUID.class), rs.getString("alias")))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    boolean usernameTaken(UUID businessId, String username, UUID exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM customer
                                       WHERE business_id = :businessId AND lower(username) = lower(:username)
                                         AND id <> :exceptId)
                        """)
                .param("businessId", businessId)
                .param("username", username)
                .param("exceptId", exceptId)
                .query(Boolean.class)
                .single();
    }

    Optional<CustomerRow> find(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE c.business_id = :businessId AND c.id = :id")
                .param("businessId", businessId)
                .param("id", id)
                .query(CustomerRepository::map)
                .optional();
    }

    Optional<CustomerRow> lock(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE c.business_id = :businessId AND c.id = :id FOR UPDATE")
                .param("businessId", businessId)
                .param("id", id)
                .query(CustomerRepository::map)
                .optional();
    }

    void insert(UUID id, UUID businessId, Fields fields, UUID membershipId, Instant now) {
        bind(jdbc.sql("""
                        INSERT INTO customer
                            (id, business_id, username, name, contact_person, phone, address, payment_terms_days,
                             credit_limit, created_by_membership_id, updated_by_membership_id, created_at, updated_at)
                        VALUES (:id, :businessId, :username, :name, :contactPerson, :phone, :address,
                                :paymentTermsDays, :creditLimit, :membershipId, :membershipId, :now, :now)
                        """), fields)
                .param("id", id)
                .param("businessId", businessId)
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .update();
    }

    void update(UUID id, Fields fields, UUID membershipId, Instant now) {
        bind(jdbc.sql("""
                        UPDATE customer
                        SET username = :username, name = :name, contact_person = :contactPerson, phone = :phone,
                            address = :address, payment_terms_days = :paymentTermsDays, credit_limit = :creditLimit,
                            updated_by_membership_id = :membershipId, updated_at = :now, version = version + 1
                        WHERE id = :id
                        """), fields)
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    List<AliasRow> findAliases(UUID businessId, UUID customerId) {
        return jdbc.sql("""
                        SELECT id, alias, approved_by_membership_id, approved_at FROM customer_alias
                        WHERE business_id = :businessId AND customer_id = :customerId
                        ORDER BY normalized_alias
                        """)
                .param("businessId", businessId)
                .param("customerId", customerId)
                .query((rs, rowNum) -> new AliasRow(rs.getObject("id", UUID.class), rs.getString("alias"),
                        rs.getObject("approved_by_membership_id", UUID.class),
                        rs.getTimestamp("approved_at").toInstant()))
                .list();
    }

    boolean aliasExists(UUID businessId, UUID customerId, String normalizedAlias) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM customer_alias
                                       WHERE business_id = :businessId AND customer_id = :customerId
                                         AND normalized_alias = :normalized)
                        """)
                .param("businessId", businessId)
                .param("customerId", customerId)
                .param("normalized", normalizedAlias)
                .query(Boolean.class)
                .single();
    }

    void insertAlias(UUID id, UUID businessId, UUID customerId, String alias, String normalizedAlias,
            UUID approvedBy, Instant now) {
        jdbc.sql("""
                        INSERT INTO customer_alias
                            (id, business_id, customer_id, alias, normalized_alias, approved_by_membership_id,
                             approved_at)
                        VALUES (:id, :businessId, :customerId, :alias, :normalized, :approvedBy, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("customerId", customerId)
                .param("alias", alias)
                .param("normalized", normalizedAlias)
                .param("approvedBy", approvedBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Fields fields) {
        return spec.param("username", fields.username())
                .param("name", fields.name())
                .param("contactPerson", fields.contactPerson())
                .param("phone", fields.phone())
                .param("address", fields.address())
                .param("paymentTermsDays", fields.paymentTermsDays(), Types.INTEGER)
                .param("creditLimit", fields.creditLimit(), Types.BIGINT);
    }

    private static CustomerRow map(ResultSet rs, int rowNum) throws SQLException {
        Fields fields = new Fields(
                rs.getString("username"),
                rs.getString("name"),
                rs.getString("contact_person"),
                rs.getString("phone"),
                rs.getString("address"),
                rs.getObject("payment_terms_days", Integer.class),
                rs.getObject("credit_limit", Long.class));
        return new CustomerRow(
                rs.getObject("id", UUID.class),
                fields,
                rs.getObject("created_by_membership_id", UUID.class),
                rs.getObject("updated_by_membership_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
