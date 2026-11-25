package org.myorg.dispatchpay.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Who performed a mutation: a member of the business, or the system. A system actor never carries
 * a membership, so background work cannot impersonate a user; it records the user who caused it as
 * the initiator instead.
 */
public record AuditActor(Type type, UUID membershipId) {

    public enum Type {
        USER, SYSTEM
    }

    public AuditActor {
        Objects.requireNonNull(type, "type");
        if ((type == Type.USER) != (membershipId != null)) {
            throw new IllegalArgumentException("A USER actor needs a membership and a SYSTEM actor must not have one");
        }
    }

    public static AuditActor user(UUID membershipId) {
        return new AuditActor(Type.USER, Objects.requireNonNull(membershipId, "membershipId"));
    }

    public static AuditActor system() {
        return new AuditActor(Type.SYSTEM, null);
    }
}
