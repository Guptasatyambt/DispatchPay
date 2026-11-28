package org.myorg.dispatchpay.order;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Sales orders of one business with their lines, charges, cancellations and revisions. */
@Repository
class OrderRepository {

    record OrderRow(
            UUID id,
            String number,
            OrderStatus status,
            UUID customerId,
            String customerName,
            UUID godownId,
            String godownName,
            LocalDate requestedDate,
            LocalDate promisedDate,
            String note,
            int revision,
            UUID createdByMembershipId,
            UUID updatedByMembershipId,
            UUID confirmedByMembershipId,
            Instant createdAt,
            Instant updatedAt,
            Instant confirmedAt,
            long version) {
    }

    /**
     * Quantities are in the base unit except {@code quantity} (in {@code unit}). {@code productPrice}
     * is the product's current price of one base unit, used while a draft line has no price.
     */
    record LineRow(
            UUID id,
            UUID orderId,
            int lineNo,
            UUID productId,
            String sku,
            String productName,
            String baseUnit,
            String productStatus,
            Long productPrice,
            BigDecimal quantity,
            String unit,
            BigDecimal unitFactor,
            BigDecimal baseQuantity,
            Long unitPrice,
            BigDecimal discountPercent,
            BigDecimal taxRate,
            BigDecimal dispatchedQuantity,
            BigDecimal cancelledQuantity) {

        BigDecimal remaining() {
            return baseQuantity.subtract(dispatchedQuantity).subtract(cancelledQuantity);
        }
    }

    record NewLine(UUID id, int lineNo, UUID productId, BigDecimal quantity, String unit, BigDecimal unitFactor,
            BigDecimal baseQuantity, Long unitPrice, BigDecimal discountPercent, BigDecimal taxRate) {
    }

    record ChargeRow(UUID orderId, int position, String label, long amount) {
    }

    record CancellationRow(UUID id, CancellationReason reason, String note, UUID cancelledByMembershipId,
            Instant cancelledAt) {
    }

    record CancellationLineRow(UUID cancellationId, UUID orderLineId, BigDecimal quantity, String unit,
            BigDecimal baseQuantity) {
    }

    record DispatchRef(UUID id, String status, Instant createdAt) {
    }

    /** Filters of {@link #search}; {@code null} fields do not filter. Dates are inclusive. */
    record OrderFilter(Set<OrderStatus> statuses, UUID customerId, UUID godownId, LocalDate requestedFrom,
            LocalDate requestedTo, String numberPrefix) {
    }

    /** Where a page of {@link #search} ends: newest first by creation, then id. */
    record OrderPosition(Instant createdAt, UUID id) {
    }

    private static final String SELECT = """
            SELECT o.id, o.number, o.status, o.customer_id, c.name AS customer_name, o.godown_id,
                   g.name AS godown_name, o.requested_date, o.promised_date, o.note, o.revision,
                   o.created_by_membership_id, o.updated_by_membership_id, o.confirmed_by_membership_id,
                   o.created_at, o.updated_at, o.confirmed_at, o.version
            FROM sales_order o
            JOIN customer c ON c.business_id = o.business_id AND c.id = o.customer_id
            JOIN godown g ON g.business_id = o.business_id AND g.id = o.godown_id
            """;

    /**
     * Restricts {@code alias}.godown_id to the caller's godown scope: every godown when
     * {@code :allGodowns}, otherwise those assigned to {@code :membershipId}.
     */
    private static String scoped(String alias) {
        return " AND (:allGodowns OR " + alias + ".godown_id IN (SELECT godown_id FROM membership_godown"
                + " WHERE business_id = :businessId AND membership_id = :membershipId))";
    }

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- orders -----------------------------------------------------------------------------------

    void insert(UUID id, UUID businessId, UUID customerId, UUID godownId, LocalDate requestedDate,
            LocalDate promisedDate, String note, UUID membershipId, Instant now) {
        jdbc.sql("""
                        INSERT INTO sales_order (id, business_id, status, customer_id, godown_id, requested_date,
                                                 promised_date, note, created_by_membership_id,
                                                 updated_by_membership_id, created_at, updated_at)
                        VALUES (:id, :businessId, 'DRAFT', :customerId, :godownId, :requestedDate, :promisedDate,
                                :note, :membershipId, :membershipId, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("customerId", customerId)
                .param("godownId", godownId)
                .param("requestedDate", requestedDate)
                .param("promisedDate", promisedDate)
                .param("note", note)
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .update();
    }

    Optional<OrderRow> find(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE o.business_id = :businessId AND o.id = :id")
                .param("businessId", businessId)
                .param("id", id)
                .query(OrderRepository::mapOrder)
                .optional();
    }

    /** The order, locked until the transaction ends. */
    Optional<OrderRow> lock(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE o.business_id = :businessId AND o.id = :id FOR UPDATE OF o")
                .param("businessId", businessId)
                .param("id", id)
                .query(OrderRepository::mapOrder)
                .optional();
    }

    /** Replaces the header of a draft. */
    void updateDraft(UUID id, UUID customerId, UUID godownId, LocalDate requestedDate, LocalDate promisedDate,
            String note, UUID membershipId, Instant now) {
        jdbc.sql("""
                        UPDATE sales_order
                        SET customer_id = :customerId, godown_id = :godownId, requested_date = :requestedDate,
                            promised_date = :promisedDate, note = :note, updated_by_membership_id = :membershipId,
                            updated_at = :now, version = version + 1
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("customerId", customerId)
                .param("godownId", godownId)
                .param("requestedDate", requestedDate)
                .param("promisedDate", promisedDate)
                .param("note", note)
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Changes what a confirmed order allows, as the next revision. */
    void updateCommitted(UUID id, LocalDate promisedDate, String note, int revision, UUID membershipId,
            Instant now) {
        jdbc.sql("""
                        UPDATE sales_order
                        SET promised_date = :promisedDate, note = :note, revision = :revision,
                            updated_by_membership_id = :membershipId, updated_at = :now, version = version + 1
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("promisedDate", promisedDate)
                .param("note", note)
                .param("revision", revision)
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .update();
    }

    void markConfirmed(UUID id, String number, UUID membershipId, Instant now) {
        jdbc.sql("""
                        UPDATE sales_order
                        SET status = 'CONFIRMED', number = :number, revision = 1,
                            confirmed_by_membership_id = :membershipId, confirmed_at = :now,
                            updated_by_membership_id = :membershipId, updated_at = :now, version = version + 1
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("number", number)
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .update();
    }

    void updateStatus(UUID id, OrderStatus status, int revision, UUID membershipId, Instant now) {
        jdbc.sql("""
                        UPDATE sales_order
                        SET status = :status, revision = :revision, updated_by_membership_id = :membershipId,
                            updated_at = :now, version = version + 1
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status.name())
                .param("revision", revision)
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** The next order number of the business, counting from 1. */
    long nextNumber(UUID businessId) {
        return jdbc.sql("""
                        INSERT INTO sales_order_number (business_id, last_number) VALUES (:businessId, 1)
                        ON CONFLICT (business_id) DO UPDATE SET last_number = sales_order_number.last_number + 1
                        RETURNING last_number
                        """)
                .param("businessId", businessId)
                .query(Long.class)
                .single();
    }

    /** A page of orders, newest first, after {@code after} when given; at most {@code limit} rows. */
    List<OrderRow> search(UUID businessId, boolean allGodowns, UUID membershipId, OrderFilter filter,
            OrderPosition after, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE o.business_id = :businessId");
        sql.append(scoped("o"));
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            sql.append(" AND o.status IN (:statuses)");
        }
        if (filter.customerId() != null) {
            sql.append(" AND o.customer_id = :customerId");
        }
        if (filter.godownId() != null) {
            sql.append(" AND o.godown_id = :godownId");
        }
        if (filter.requestedFrom() != null) {
            sql.append(" AND o.requested_date >= :requestedFrom");
        }
        if (filter.requestedTo() != null) {
            sql.append(" AND o.requested_date <= :requestedTo");
        }
        if (filter.numberPrefix() != null) {
            sql.append(" AND lower(o.number) LIKE :numberPrefix ESCAPE '\\'");
        }
        if (after != null) {
            sql.append(" AND (o.created_at, o.id) < (:afterAt, :afterId)");
        }
        sql.append(" ORDER BY o.created_at DESC, o.id DESC LIMIT :limit");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString())
                .param("businessId", businessId)
                .param("allGodowns", allGodowns)
                .param("membershipId", membershipId)
                .param("limit", limit);
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            statement = statement.param("statuses", filter.statuses().stream().map(Enum::name).toList());
        }
        if (filter.customerId() != null) {
            statement = statement.param("customerId", filter.customerId());
        }
        if (filter.godownId() != null) {
            statement = statement.param("godownId", filter.godownId());
        }
        if (filter.requestedFrom() != null) {
            statement = statement.param("requestedFrom", filter.requestedFrom());
        }
        if (filter.requestedTo() != null) {
            statement = statement.param("requestedTo", filter.requestedTo());
        }
        if (filter.numberPrefix() != null) {
            String escaped = filter.numberPrefix().toLowerCase(java.util.Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            statement = statement.param("numberPrefix", escaped + "%");
        }
        if (after != null) {
            statement = statement.param("afterAt", Timestamp.from(after.createdAt())).param("afterId", after.id());
        }
        return statement.query(OrderRepository::mapOrder).list();
    }

    /** Confirmed orders of the customer that are not closed, except {@code exceptOrderId}. */
    List<UUID> openOrdersOfCustomer(UUID businessId, UUID customerId, UUID exceptOrderId) {
        return jdbc.sql("""
                        SELECT id FROM sales_order
                        WHERE business_id = :businessId AND customer_id = :customerId AND id <> :exceptId
                          AND status IN ('CONFIRMED', 'PARTIALLY_DISPATCHED')
                        """)
                .param("businessId", businessId)
                .param("customerId", customerId)
                .param("exceptId", exceptOrderId)
                .query(UUID.class)
                .list();
    }

    // --- lines and charges ------------------------------------------------------------------------

    void insertLines(UUID businessId, UUID orderId, List<NewLine> lines) {
        for (NewLine line : lines) {
            jdbc.sql("""
                            INSERT INTO sales_order_line (id, business_id, order_id, line_no, product_id, quantity,
                                                          unit, unit_factor, base_quantity, unit_price,
                                                          discount_percent, tax_rate)
                            VALUES (:id, :businessId, :orderId, :lineNo, :productId, :quantity, :unit, :unitFactor,
                                    :baseQuantity, :unitPrice, :discountPercent, :taxRate)
                            """)
                    .param("id", line.id())
                    .param("businessId", businessId)
                    .param("orderId", orderId)
                    .param("lineNo", line.lineNo())
                    .param("productId", line.productId())
                    .param("quantity", line.quantity())
                    .param("unit", line.unit())
                    .param("unitFactor", line.unitFactor())
                    .param("baseQuantity", line.baseQuantity())
                    .param("unitPrice", line.unitPrice())
                    .param("discountPercent", line.discountPercent())
                    .param("taxRate", line.taxRate())
                    .update();
        }
    }

    void deleteLines(UUID orderId) {
        jdbc.sql("DELETE FROM sales_order_line WHERE order_id = :orderId").param("orderId", orderId).update();
    }

    /** Freezes the price and current unit conversion of a line at confirmation. */
    void commitLine(UUID lineId, long unitPrice, BigDecimal unitFactor, BigDecimal baseQuantity) {
        jdbc.sql("""
                        UPDATE sales_order_line
                        SET unit_price = :unitPrice, unit_factor = :unitFactor, base_quantity = :baseQuantity
                        WHERE id = :id
                        """)
                .param("id", lineId)
                .param("unitPrice", unitPrice)
                .param("unitFactor", unitFactor)
                .param("baseQuantity", baseQuantity)
                .update();
    }

    void addCancelled(UUID lineId, BigDecimal baseQuantity) {
        jdbc.sql("UPDATE sales_order_line SET cancelled_quantity = cancelled_quantity + :quantity WHERE id = :id")
                .param("id", lineId)
                .param("quantity", baseQuantity)
                .update();
    }

    /** Lines of the orders in line order, by order. */
    Map<UUID, List<LineRow>> findLines(UUID businessId, Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT l.id, l.order_id, l.line_no, l.product_id, p.sku, p.name AS product_name,
                               p.base_unit, p.status AS product_status, p.price AS product_price, l.quantity, l.unit,
                               l.unit_factor, l.base_quantity, l.unit_price, l.discount_percent, l.tax_rate,
                               l.dispatched_quantity, l.cancelled_quantity
                        FROM sales_order_line l
                        JOIN product p ON p.business_id = l.business_id AND p.id = l.product_id
                        WHERE l.business_id = :businessId AND l.order_id IN (:orderIds)
                        ORDER BY l.order_id, l.line_no
                        """)
                .param("businessId", businessId)
                .param("orderIds", orderIds)
                .query(OrderRepository::mapLine)
                .list().stream()
                .collect(Collectors.groupingBy(LineRow::orderId, Collectors.toList()));
    }

    void insertCharges(UUID businessId, UUID orderId, List<ChargeRow> charges) {
        for (ChargeRow charge : charges) {
            jdbc.sql("""
                            INSERT INTO sales_order_charge (id, business_id, order_id, position, label, amount)
                            VALUES (:id, :businessId, :orderId, :position, :label, :amount)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("businessId", businessId)
                    .param("orderId", orderId)
                    .param("position", charge.position())
                    .param("label", charge.label())
                    .param("amount", charge.amount())
                    .update();
        }
    }

    void deleteCharges(UUID orderId) {
        jdbc.sql("DELETE FROM sales_order_charge WHERE order_id = :orderId").param("orderId", orderId).update();
    }

    Map<UUID, List<ChargeRow>> findCharges(UUID businessId, Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT order_id, position, label, amount FROM sales_order_charge
                        WHERE business_id = :businessId AND order_id IN (:orderIds)
                        ORDER BY order_id, position
                        """)
                .param("businessId", businessId)
                .param("orderIds", orderIds)
                .query((rs, rowNum) -> new ChargeRow(rs.getObject("order_id", UUID.class), rs.getInt("position"),
                        rs.getString("label"), rs.getLong("amount")))
                .list().stream()
                .collect(Collectors.groupingBy(ChargeRow::orderId, Collectors.toList()));
    }

    // --- cancellations, revisions and dispatches --------------------------------------------------

    void insertCancellation(UUID id, UUID businessId, UUID orderId, CancellationReason reason, String note,
            UUID membershipId, Instant now) {
        jdbc.sql("""
                        INSERT INTO sales_order_cancellation (id, business_id, order_id, reason, note,
                                                              cancelled_by_membership_id, cancelled_at)
                        VALUES (:id, :businessId, :orderId, :reason, :note, :membershipId, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("orderId", orderId)
                .param("reason", reason.name())
                .param("note", note)
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .update();
    }

    void insertCancellationLine(UUID cancellationId, UUID businessId, UUID orderLineId, BigDecimal quantity,
            String unit, BigDecimal baseQuantity) {
        jdbc.sql("""
                        INSERT INTO sales_order_cancellation_line (cancellation_id, business_id, order_line_id,
                                                                   quantity, unit, base_quantity)
                        VALUES (:cancellationId, :businessId, :orderLineId, :quantity, :unit, :baseQuantity)
                        """)
                .param("cancellationId", cancellationId)
                .param("businessId", businessId)
                .param("orderLineId", orderLineId)
                .param("quantity", quantity)
                .param("unit", unit)
                .param("baseQuantity", baseQuantity)
                .update();
    }

    List<CancellationRow> findCancellations(UUID businessId, UUID orderId) {
        return jdbc.sql("""
                        SELECT id, reason, note, cancelled_by_membership_id, cancelled_at
                        FROM sales_order_cancellation
                        WHERE business_id = :businessId AND order_id = :orderId
                        ORDER BY cancelled_at, id
                        """)
                .param("businessId", businessId)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new CancellationRow(rs.getObject("id", UUID.class),
                        CancellationReason.valueOf(rs.getString("reason")), rs.getString("note"),
                        rs.getObject("cancelled_by_membership_id", UUID.class), instant(rs, "cancelled_at")))
                .list();
    }

    List<CancellationLineRow> findCancellationLines(UUID businessId, UUID orderId) {
        return jdbc.sql("""
                        SELECT cl.cancellation_id, cl.order_line_id, cl.quantity, cl.unit, cl.base_quantity
                        FROM sales_order_cancellation_line cl
                        JOIN sales_order_line l ON l.id = cl.order_line_id
                        WHERE cl.business_id = :businessId AND l.order_id = :orderId
                        ORDER BY l.line_no
                        """)
                .param("businessId", businessId)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new CancellationLineRow(rs.getObject("cancellation_id", UUID.class),
                        rs.getObject("order_line_id", UUID.class), rs.getBigDecimal("quantity"), rs.getString("unit"),
                        rs.getBigDecimal("base_quantity")))
                .list();
    }

    void insertRevision(UUID orderId, int revision, UUID businessId, String change, String snapshot,
            UUID membershipId, Instant now) {
        jdbc.sql("""
                        INSERT INTO sales_order_revision (order_id, revision, business_id, change, snapshot,
                                                          created_by_membership_id, created_at)
                        VALUES (:orderId, :revision, :businessId, :change, CAST(:snapshot AS jsonb), :membershipId,
                                :now)
                        """)
                .param("orderId", orderId)
                .param("revision", revision)
                .param("businessId", businessId)
                .param("change", change)
                .param("snapshot", snapshot)
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Dispatches created from the order, oldest first. */
    List<DispatchRef> findDispatches(UUID businessId, UUID orderId) {
        return jdbc.sql("""
                        SELECT id, status, created_at FROM dispatch
                        WHERE business_id = :businessId AND order_id = :orderId
                        ORDER BY created_at, id
                        """)
                .param("businessId", businessId)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new DispatchRef(rs.getObject("id", UUID.class), rs.getString("status"),
                        instant(rs, "created_at")))
                .list();
    }

    // --- mapping ----------------------------------------------------------------------------------

    private static OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getObject("id", UUID.class),
                rs.getString("number"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name"),
                rs.getObject("godown_id", UUID.class),
                rs.getString("godown_name"),
                rs.getObject("requested_date", LocalDate.class),
                rs.getObject("promised_date", LocalDate.class),
                rs.getString("note"),
                rs.getInt("revision"),
                rs.getObject("created_by_membership_id", UUID.class),
                rs.getObject("updated_by_membership_id", UUID.class),
                rs.getObject("confirmed_by_membership_id", UUID.class),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "confirmed_at"),
                rs.getLong("version"));
    }

    private static LineRow mapLine(ResultSet rs, int rowNum) throws SQLException {
        long productPrice = rs.getLong("product_price");
        Long price = rs.wasNull() ? null : productPrice;
        long unitPrice = rs.getLong("unit_price");
        Long linePrice = rs.wasNull() ? null : unitPrice;
        return new LineRow(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getInt("line_no"),
                rs.getObject("product_id", UUID.class),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("base_unit"),
                rs.getString("product_status"),
                price,
                rs.getBigDecimal("quantity"),
                rs.getString("unit"),
                rs.getBigDecimal("unit_factor"),
                rs.getBigDecimal("base_quantity"),
                linePrice,
                rs.getBigDecimal("discount_percent"),
                rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("dispatched_quantity"),
                rs.getBigDecimal("cancelled_quantity"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
