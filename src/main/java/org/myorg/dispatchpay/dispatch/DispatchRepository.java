package org.myorg.dispatchpay.dispatch;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Dispatches of one business and their lines. */
@Repository
class DispatchRepository {

    private final JdbcClient jdbc;

    DispatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertDraft(UUID id, UUID businessId, UUID customerId, UUID godownId, UUID orderId, String note,
            UUID membershipId, Instant now) {
        jdbc.sql("""
                        INSERT INTO dispatch (id, business_id, status, customer_id, godown_id, order_id, note,
                                              created_by_membership_id, updated_by_membership_id, created_at,
                                              updated_at)
                        VALUES (:id, :businessId, 'DRAFT', :customerId, :godownId, :orderId, :note, :membershipId,
                                :membershipId, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("customerId", customerId)
                .param("godownId", godownId)
                .param("orderId", orderId)
                .param("note", note)
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .update();
    }

    void insertLine(UUID id, UUID businessId, UUID dispatchId, int lineNo, UUID productId, UUID orderLineId,
            BigDecimal quantity, String unit, BigDecimal unitFactor, BigDecimal baseQuantity) {
        jdbc.sql("""
                        INSERT INTO dispatch_line (id, business_id, dispatch_id, line_no, product_id, order_line_id,
                                                   quantity, unit, unit_factor, base_quantity)
                        VALUES (:id, :businessId, :dispatchId, :lineNo, :productId, :orderLineId, :quantity, :unit,
                                :unitFactor, :baseQuantity)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("dispatchId", dispatchId)
                .param("lineNo", lineNo)
                .param("productId", productId)
                .param("orderLineId", orderLineId)
                .param("quantity", quantity)
                .param("unit", unit)
                .param("unitFactor", unitFactor)
                .param("baseQuantity", baseQuantity)
                .update();
    }
}
