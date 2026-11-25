package org.myorg.dispatchpay.iam.access;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;

/**
 * The caller's current access to the business selected with {@value #HEADER}. Resolved from the
 * database on every request, never from the access token, so revoked grants and suspensions apply
 * immediately. Declare it as a controller parameter to require a selected, accessible business.
 */
public record BusinessAccess(
        UUID businessId,
        UUID membershipId,
        UUID userId,
        Role role,
        Set<Permission> permissions,
        GodownScope godownScope) {

    public static final String HEADER = "X-Business-Id";

    public enum Role {
        OWNER, WORKER
    }

    /** Godowns the membership may act in. {@code all} for the owner; workers are limited to the listed ids. */
    public record GodownScope(boolean all, List<UUID> godownIds) {

        public static final GodownScope ALL = new GodownScope(true, List.of());
        public static final GodownScope NONE = new GodownScope(false, List.of());

        public boolean includes(UUID godownId) {
            return all || godownIds.contains(godownId);
        }
    }

    public BusinessAccess {
        permissions = Set.copyOf(permissions);
    }

    public boolean isOwner() {
        return role == Role.OWNER;
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    public void require(Permission permission) {
        if (!has(permission)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You do not have permission to perform this action");
        }
    }

    public void requireOwner() {
        if (!isOwner()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the business owner can perform this action");
        }
    }
}
