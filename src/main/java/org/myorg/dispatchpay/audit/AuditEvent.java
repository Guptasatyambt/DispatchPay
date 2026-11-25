package org.myorg.dispatchpay.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One committed mutation in a business.
 *
 * @param initiator who caused the mutation (for a direct action, the actor; for background work,
 *                  the user whose request started it)
 * @param approver  who approved it, when the action needs approval
 * @param changes   field diff; values of sensitive fields are redacted before storage
 */
public record AuditEvent(
        UUID businessId,
        AuditActor actor,
        UUID initiatorMembershipId,
        UUID approverMembershipId,
        AuditSource source,
        String action,
        String resourceType,
        String resourceId,
        Map<String, AuditChange> changes) {

    public AuditEvent {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        changes = changes == null ? Map.of() : new LinkedHashMap<>(changes);
    }

    /** A member's own direct action through the API: actor and initiator are the same membership. */
    public static AuditEvent manual(UUID businessId, UUID membershipId, String action, String resourceType,
            Object resourceId, Map<String, AuditChange> changes) {
        return new AuditEvent(businessId, AuditActor.user(membershipId), membershipId, null, AuditSource.MANUAL,
                action, resourceType, resourceId.toString(), changes);
    }
}
