package org.myorg.dispatchpay.dispatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.myorg.dispatchpay.audit.AuditChange;
import org.myorg.dispatchpay.audit.AuditEvent;
import org.myorg.dispatchpay.audit.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispatches: the documents that send goods to a customer. A draft has no stock effect; only its
 * confirmation (M07) posts stock movements, and that confirmation rechecks every order-linked line
 * against the order's remaining quantity, so competing drafts cannot over-fulfil an order.
 */
@Service
public class DispatchService {

    static final String RESOURCE = "dispatch";

    /**
     * A line of a new draft, already validated by the caller.
     *
     * @param orderLineId the order line it fulfils, or {@code null} for a direct dispatch
     * @param quantity    as entered, in {@code unit}
     * @param unitFactor  base units in one {@code unit}
     */
    public record DraftLine(UUID productId, String sku, String baseUnit, UUID orderLineId, BigDecimal quantity,
            String unit, BigDecimal unitFactor, BigDecimal baseQuantity) {
    }

    public record DispatchLineView(
            int line,
            UUID id,
            UUID orderLineId,
            UUID productId,
            String sku,
            BigDecimal quantity,
            String unit,
            BigDecimal unitFactor,
            BigDecimal baseQuantity,
            String baseUnit) {
    }

    public record DispatchView(
            UUID id,
            DispatchStatus status,
            UUID orderId,
            UUID customerId,
            UUID godownId,
            String note,
            List<DispatchLineView> lines,
            UUID createdByMembershipId,
            Instant createdAt,
            long version) {
    }

    private final DispatchRepository dispatches;
    private final AuditWriter audit;

    public DispatchService(DispatchRepository dispatches, AuditWriter audit) {
        this.dispatches = dispatches;
        this.audit = audit;
    }

    /**
     * Creates a draft dispatch, audited in the caller's transaction. The caller has checked the
     * permission, godown scope, customer and lines.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DispatchView createDraft(UUID businessId, UUID membershipId, UUID customerId, UUID godownId, UUID orderId,
            String note, List<DraftLine> lines, Instant now) {
        UUID id = UUID.randomUUID();
        dispatches.insertDraft(id, businessId, customerId, godownId, orderId, note, membershipId, now);
        List<DispatchLineView> views = new ArrayList<>();
        List<Map<String, Object>> auditLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            DraftLine line = lines.get(i);
            UUID lineId = UUID.randomUUID();
            dispatches.insertLine(lineId, businessId, id, i + 1, line.productId(), line.orderLineId(),
                    line.quantity(), line.unit(), line.unitFactor(), line.baseQuantity());
            views.add(new DispatchLineView(i + 1, lineId, line.orderLineId(), line.productId(), line.sku(),
                    line.quantity(), line.unit(), line.unitFactor(), line.baseQuantity(), line.baseUnit()));
            Map<String, Object> auditLine = new LinkedHashMap<>();
            auditLine.put("orderLineId", line.orderLineId());
            auditLine.put("productId", line.productId());
            auditLine.put("quantity", line.quantity());
            auditLine.put("unit", line.unit());
            auditLine.put("baseQuantity", line.baseQuantity());
            auditLines.add(auditLine);
        }
        Map<String, AuditChange> changes = new LinkedHashMap<>();
        changes.put("status", new AuditChange(null, DispatchStatus.DRAFT.name()));
        changes.put("orderId", new AuditChange(null, orderId));
        changes.put("customerId", new AuditChange(null, customerId));
        changes.put("godownId", new AuditChange(null, godownId));
        if (note != null) {
            changes.put("note", new AuditChange(null, note));
        }
        changes.put("lines", new AuditChange(null, auditLines));
        audit.append(AuditEvent.manual(businessId, membershipId, "dispatch.create", RESOURCE, id, changes));
        return new DispatchView(id, DispatchStatus.DRAFT, orderId, customerId, godownId, note, views, membershipId,
                now, 0);
    }
}
