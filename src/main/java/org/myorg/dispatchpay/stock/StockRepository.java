package org.myorg.dispatchpay.stock;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Ledger, balances and stock documents. Every business query is filtered by the business. */
@Repository
class StockRepository {

    record LockedBalance(BigDecimal quantity, Long lastMovementSeq) {
    }

    record BalanceRow(
            UUID godownId,
            String godownName,
            UUID productId,
            String sku,
            String productName,
            String baseUnit,
            String productStatus,
            BigDecimal quantity,
            Instant updatedAt) {
    }

    record MovementRow(
            UUID id,
            long seq,
            StockMovementType type,
            UUID godownId,
            UUID productId,
            String sku,
            String baseUnit,
            BigDecimal quantity,
            BigDecimal enteredQuantity,
            String enteredUnit,
            BigDecimal unitFactor,
            BigDecimal balanceAfter,
            String sourceType,
            UUID sourceId,
            int sourceLine,
            String actorType,
            UUID actorMembershipId,
            UUID initiatorMembershipId,
            String reference,
            String reason,
            String note,
            Instant postedAt) {
    }

    record NewMovement(
            UUID id,
            UUID businessId,
            UUID godownId,
            UUID productId,
            StockMovementType type,
            BigDecimal quantity,
            BigDecimal enteredQuantity,
            String enteredUnit,
            BigDecimal unitFactor,
            BigDecimal balanceAfter,
            String sourceType,
            UUID sourceId,
            int sourceLine,
            UUID actorMembershipId,
            UUID initiatorMembershipId,
            Instant postedAt) {
    }

    /** Filters of a movement history read; {@code null} fields do not filter. */
    record MovementFilter(UUID godownId, UUID productId, StockMovementType type, Instant from, Instant to) {
    }

    /** The movement a history page continues after (exclusive). */
    record MovementPosition(Instant postedAt, long seq) {
    }

    /** A pair whose balance disagrees with the sum of its movements; either side may be missing. */
    record Variance(UUID businessId, UUID godownId, UUID productId, BigDecimal balance, BigDecimal movements) {
    }

    /**
     * Restricts {@code alias}.godown_id to the caller's godown scope: every godown when
     * {@code :allGodowns}, otherwise those assigned to {@code :membershipId}.
     */
    private static String scoped(String alias) {
        return " AND (:allGodowns OR " + alias + ".godown_id IN (SELECT godown_id FROM membership_godown"
                + " WHERE business_id = :businessId AND membership_id = :membershipId))";
    }

    private final JdbcClient jdbc;

    StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- posting ----------------------------------------------------------------------------------

    /**
     * Locks the balance of {@code key} for the rest of the transaction, creating it at zero when
     * the pair has none. Callers lock keys in one global order so postings cannot deadlock.
     */
    LockedBalance lockBalance(UUID businessId, StockKey key, Instant now) {
        jdbc.sql("""
                        INSERT INTO stock_balance (business_id, godown_id, product_id, quantity, updated_at)
                        VALUES (:businessId, :godownId, :productId, 0, :now)
                        ON CONFLICT (business_id, godown_id, product_id) DO NOTHING
                        """)
                .param("businessId", businessId)
                .param("godownId", key.godownId())
                .param("productId", key.productId())
                .param("now", Timestamp.from(now))
                .update();
        return jdbc.sql("""
                        SELECT quantity, last_movement_seq FROM stock_balance
                        WHERE business_id = :businessId AND godown_id = :godownId AND product_id = :productId
                        FOR UPDATE
                        """)
                .param("businessId", businessId)
                .param("godownId", key.godownId())
                .param("productId", key.productId())
                .query((rs, rowNum) -> new LockedBalance(rs.getBigDecimal("quantity"),
                        rs.getObject("last_movement_seq", Long.class)))
                .single();
    }

    /** Returns the movement's sequence number. */
    long insertMovement(NewMovement movement) {
        return jdbc.sql("""
                        INSERT INTO stock_movement (id, business_id, godown_id, product_id, type, quantity,
                                                    entered_quantity, entered_unit, unit_factor, balance_after,
                                                    source_type, source_id, source_line, actor_type,
                                                    actor_membership_id, initiator_membership_id, posted_at)
                        VALUES (:id, :businessId, :godownId, :productId, :type, :quantity, :enteredQuantity,
                                :enteredUnit, :unitFactor, :balanceAfter, :sourceType, :sourceId, :sourceLine,
                                :actorType, :actorMembershipId, :initiatorMembershipId, :postedAt)
                        RETURNING seq
                        """)
                .param("id", movement.id())
                .param("businessId", movement.businessId())
                .param("godownId", movement.godownId())
                .param("productId", movement.productId())
                .param("type", movement.type().name())
                .param("quantity", movement.quantity())
                .param("enteredQuantity", movement.enteredQuantity())
                .param("enteredUnit", movement.enteredUnit())
                .param("unitFactor", movement.unitFactor())
                .param("balanceAfter", movement.balanceAfter())
                .param("sourceType", movement.sourceType())
                .param("sourceId", movement.sourceId())
                .param("sourceLine", movement.sourceLine())
                .param("actorType", movement.actorMembershipId() == null ? "SYSTEM" : "USER")
                .param("actorMembershipId", movement.actorMembershipId())
                .param("initiatorMembershipId", movement.initiatorMembershipId())
                .param("postedAt", Timestamp.from(movement.postedAt()))
                .query(Long.class)
                .single();
    }

    void updateBalance(UUID businessId, StockKey key, BigDecimal quantity, long lastMovementSeq, Instant now) {
        jdbc.sql("""
                        UPDATE stock_balance SET quantity = :quantity, last_movement_seq = :seq, updated_at = :now
                        WHERE business_id = :businessId AND godown_id = :godownId AND product_id = :productId
                        """)
                .param("quantity", quantity)
                .param("seq", lastMovementSeq)
                .param("now", Timestamp.from(now))
                .param("businessId", businessId)
                .param("godownId", key.godownId())
                .param("productId", key.productId())
                .update();
    }

    void insertDocument(UUID id, UUID businessId, StockMovementType type, UUID godownId, String reference,
            AdjustmentReason reason, String note, UUID membershipId, Instant postedAt) {
        jdbc.sql("""
                        INSERT INTO stock_document (id, business_id, type, godown_id, reference, reason, note,
                                                    created_by_membership_id, posted_at)
                        VALUES (:id, :businessId, :type, :godownId, :reference, :reason, :note, :membershipId,
                                :postedAt)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("type", type.name())
                .param("godownId", godownId)
                .param("reference", reference)
                .param("reason", reason == null ? null : reason.name())
                .param("note", note)
                .param("membershipId", membershipId)
                .param("postedAt", Timestamp.from(postedAt))
                .update();
    }

    /** Which of the keys have had any movement (without locking). */
    Set<StockKey> keysWithMovements(UUID businessId, Collection<StockKey> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        List<UUID> godownIds = keys.stream().map(StockKey::godownId).distinct().toList();
        List<UUID> productIds = keys.stream().map(StockKey::productId).distinct().toList();
        Set<StockKey> found = new HashSet<>(jdbc.sql("""
                        SELECT godown_id, product_id FROM stock_balance
                        WHERE business_id = :businessId AND godown_id IN (:godownIds)
                          AND product_id IN (:productIds) AND last_movement_seq IS NOT NULL
                        """)
                .param("businessId", businessId)
                .param("godownIds", godownIds)
                .param("productIds", productIds)
                .query((rs, rowNum) -> new StockKey(rs.getObject("godown_id", UUID.class),
                        rs.getObject("product_id", UUID.class)))
                .list());
        found.retainAll(new HashSet<>(keys));
        return found;
    }

    // --- reads ------------------------------------------------------------------------------------

    List<BalanceRow> findBalances(UUID businessId, boolean allGodowns, UUID membershipId, UUID godownId,
            UUID productId, boolean nonZero, long offset, int limit) {
        String sql = """
                SELECT b.godown_id, g.name AS godown_name, b.product_id, p.sku, p.name AS product_name,
                       p.base_unit, p.status, b.quantity, b.updated_at
                FROM stock_balance b
                JOIN godown g ON g.business_id = b.business_id AND g.id = b.godown_id
                JOIN product p ON p.business_id = b.business_id AND p.id = b.product_id
                """ + balanceWhere(godownId, productId, nonZero)
                + " ORDER BY lower(g.name), g.id, lower(p.sku), p.id LIMIT :limit OFFSET :offset";
        return balanceParams(jdbc.sql(sql), businessId, allGodowns, membershipId, godownId, productId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, rowNum) -> new BalanceRow(rs.getObject("godown_id", UUID.class),
                        rs.getString("godown_name"), rs.getObject("product_id", UUID.class), rs.getString("sku"),
                        rs.getString("product_name"), rs.getString("base_unit"), rs.getString("status"),
                        rs.getBigDecimal("quantity"), rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    long countBalances(UUID businessId, boolean allGodowns, UUID membershipId, UUID godownId, UUID productId,
            boolean nonZero) {
        return balanceParams(jdbc.sql("SELECT count(*) FROM stock_balance b"
                        + balanceWhere(godownId, productId, nonZero)),
                businessId, allGodowns, membershipId, godownId, productId)
                .query(Long.class)
                .single();
    }

    private static String balanceWhere(UUID godownId, UUID productId, boolean nonZero) {
        // A balance row with no movement only exists inside the transaction that is posting its first one.
        StringBuilder where = new StringBuilder(" WHERE b.business_id = :businessId AND b.last_movement_seq IS NOT NULL");
        where.append(scoped("b"));
        if (godownId != null) {
            where.append(" AND b.godown_id = :godownId");
        }
        if (productId != null) {
            where.append(" AND b.product_id = :productId");
        }
        if (nonZero) {
            where.append(" AND b.quantity <> 0");
        }
        return where.toString();
    }

    private static JdbcClient.StatementSpec balanceParams(JdbcClient.StatementSpec spec, UUID businessId,
            boolean allGodowns, UUID membershipId, UUID godownId, UUID productId) {
        spec = spec.param("businessId", businessId)
                .param("allGodowns", allGodowns)
                .param("membershipId", membershipId);
        if (godownId != null) {
            spec = spec.param("godownId", godownId);
        }
        if (productId != null) {
            spec = spec.param("productId", productId);
        }
        return spec;
    }

    /** Newest first: by posting instant, then sequence. */
    List<MovementRow> findMovements(UUID businessId, boolean allGodowns, UUID membershipId, MovementFilter filter,
            MovementPosition after, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.seq, m.type, m.godown_id, m.product_id, p.sku, p.base_unit, m.quantity,
                       m.entered_quantity, m.entered_unit, m.unit_factor, m.balance_after, m.source_type,
                       m.source_id, m.source_line, m.actor_type, m.actor_membership_id,
                       m.initiator_membership_id, d.reference, d.reason, d.note, m.posted_at
                FROM stock_movement m
                JOIN product p ON p.business_id = m.business_id AND p.id = m.product_id
                LEFT JOIN stock_document d ON m.source_type = 'STOCK_DOCUMENT'
                                          AND d.business_id = m.business_id AND d.id = m.source_id
                WHERE m.business_id = :businessId
                """);
        sql.append(scoped("m"));
        if (filter.godownId() != null) {
            sql.append(" AND m.godown_id = :godownId");
        }
        if (filter.productId() != null) {
            sql.append(" AND m.product_id = :productId");
        }
        if (filter.type() != null) {
            sql.append(" AND m.type = :type");
        }
        if (filter.from() != null) {
            sql.append(" AND m.posted_at >= :from");
        }
        if (filter.to() != null) {
            sql.append(" AND m.posted_at < :to");
        }
        if (after != null) {
            sql.append(" AND (m.posted_at, m.seq) < (:afterAt, :afterSeq)");
        }
        sql.append(" ORDER BY m.posted_at DESC, m.seq DESC LIMIT :limit");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("businessId", businessId)
                .param("allGodowns", allGodowns)
                .param("membershipId", membershipId)
                .param("limit", limit);
        if (filter.godownId() != null) {
            spec = spec.param("godownId", filter.godownId());
        }
        if (filter.productId() != null) {
            spec = spec.param("productId", filter.productId());
        }
        if (filter.type() != null) {
            spec = spec.param("type", filter.type().name());
        }
        if (filter.from() != null) {
            spec = spec.param("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            spec = spec.param("to", Timestamp.from(filter.to()));
        }
        if (after != null) {
            spec = spec.param("afterAt", Timestamp.from(after.postedAt())).param("afterSeq", after.seq());
        }
        return spec.query(StockRepository::mapMovement).list();
    }

    private static MovementRow mapMovement(ResultSet rs, int rowNum) throws SQLException {
        return new MovementRow(rs.getObject("id", UUID.class), rs.getLong("seq"),
                StockMovementType.valueOf(rs.getString("type")), rs.getObject("godown_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("base_unit"),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("entered_quantity"), rs.getString("entered_unit"),
                rs.getBigDecimal("unit_factor"), rs.getBigDecimal("balance_after"), rs.getString("source_type"),
                rs.getObject("source_id", UUID.class), rs.getInt("source_line"), rs.getString("actor_type"),
                rs.getObject("actor_membership_id", UUID.class),
                rs.getObject("initiator_membership_id", UUID.class), rs.getString("reference"),
                rs.getString("reason"), rs.getString("note"), rs.getTimestamp("posted_at").toInstant());
    }

    // --- reconciliation ---------------------------------------------------------------------------

    /** Whether this transaction may reconcile: only one instance runs it at a time. */
    boolean tryReconciliationLock() {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('dispatchpay.stock.reconciliation'))")
                .query(Boolean.class)
                .single();
    }

    /**
     * Every pair, across all businesses, whose balance differs from the sum of its signed movements.
     * One statement, so balances and movements are read from the same snapshot.
     */
    List<Variance> findVariances() {
        return jdbc.sql("""
                        SELECT coalesce(b.business_id, m.business_id) AS business_id,
                               coalesce(b.godown_id, m.godown_id) AS godown_id,
                               coalesce(b.product_id, m.product_id) AS product_id,
                               b.quantity AS balance, m.total AS movements
                        FROM (SELECT * FROM stock_balance WHERE last_movement_seq IS NOT NULL) b
                        FULL JOIN (SELECT business_id, godown_id, product_id, sum(quantity) AS total
                                   FROM stock_movement GROUP BY business_id, godown_id, product_id) m
                          ON m.business_id = b.business_id AND m.godown_id = b.godown_id
                         AND m.product_id = b.product_id
                        WHERE b.quantity IS DISTINCT FROM m.total
                        ORDER BY 1, 2, 3
                        """)
                .query((rs, rowNum) -> new Variance(rs.getObject("business_id", UUID.class),
                        rs.getObject("godown_id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getBigDecimal("balance"), rs.getBigDecimal("movements")))
                .list();
    }

    /** Open discrepancies by pair, locked for the reconciliation run. */
    Map<StockKey, UUID> lockOpenDiscrepancies(UUID businessId) {
        Map<StockKey, UUID> open = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT id, godown_id, product_id FROM stock_discrepancy
                        WHERE business_id = :businessId AND status = 'OPEN' FOR UPDATE
                        """)
                .param("businessId", businessId)
                .query((rs, rowNum) -> Map.entry(new StockKey(rs.getObject("godown_id", UUID.class),
                        rs.getObject("product_id", UUID.class)), rs.getObject("id", UUID.class)))
                .list()
                .forEach(entry -> open.put(entry.getKey(), entry.getValue()));
        return open;
    }

    /** Businesses with an open discrepancy. */
    List<UUID> businessesWithOpenDiscrepancies() {
        return jdbc.sql("SELECT DISTINCT business_id FROM stock_discrepancy WHERE status = 'OPEN'")
                .query(UUID.class)
                .list();
    }

    void openDiscrepancy(UUID id, Variance variance, Instant now) {
        jdbc.sql("""
                        INSERT INTO stock_discrepancy (id, business_id, godown_id, product_id, balance_quantity,
                                                       movement_quantity, status, first_detected_at,
                                                       last_detected_at)
                        VALUES (:id, :businessId, :godownId, :productId, :balance, :movements, 'OPEN', :now, :now)
                        """)
                .param("id", id)
                .param("businessId", variance.businessId())
                .param("godownId", variance.godownId())
                .param("productId", variance.productId())
                .param("balance", variance.balance())
                .param("movements", variance.movements())
                .param("now", Timestamp.from(now))
                .update();
    }

    void refreshDiscrepancy(UUID id, Variance variance, Instant now) {
        jdbc.sql("""
                        UPDATE stock_discrepancy
                        SET balance_quantity = :balance, movement_quantity = :movements, last_detected_at = :now
                        WHERE id = :id
                        """)
                .param("balance", variance.balance())
                .param("movements", variance.movements())
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    void resolveDiscrepancy(UUID id, Instant now) {
        jdbc.sql("UPDATE stock_discrepancy SET status = 'RESOLVED', resolved_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    long countOpenDiscrepancies() {
        return jdbc.sql("SELECT count(*) FROM stock_discrepancy WHERE status = 'OPEN'").query(Long.class).single();
    }
}
