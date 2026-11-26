package org.myorg.dispatchpay.iam.access;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.myorg.dispatchpay.iam.access.AccessRepository.MembershipRow;
import org.myorg.dispatchpay.iam.access.BusinessAccess.GodownScope;
import org.myorg.dispatchpay.iam.access.BusinessAccess.Role;
import org.myorg.dispatchpay.iam.membership.MembershipRepository;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Evaluates what a user may do in each of their businesses, always from current database state. */
@Service
public class AccessService {

    private static final Logger log = LoggerFactory.getLogger(AccessService.class);

    /** A business the user belongs to, for business selection. */
    public record MembershipSummary(
            UUID membershipId,
            UUID businessId,
            String businessName,
            String timezone,
            Role role,
            String status) {
    }

    private final AccessRepository repository;

    public AccessService(AccessRepository repository) {
        this.repository = repository;
    }

    /** All of the user's memberships in active businesses, suspended ones included. */
    @Transactional(readOnly = true)
    public List<MembershipSummary> memberships(UUID userId) {
        return repository.findMemberships(userId).stream()
                .map(row -> new MembershipSummary(row.membershipId(), row.businessId(), row.businessName(),
                        row.timezone(), role(row), row.membershipStatus()))
                .toList();
    }

    /**
     * The user's access to one business. A business the user does not belong to is reported as
     * not found, whether or not it exists, so ids from other tenants reveal nothing.
     */
    @Transactional(readOnly = true)
    public BusinessAccess resolve(UUID userId, UUID businessId) {
        MembershipRow row = repository.findMembership(userId, businessId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Business not found"));
        if (!MembershipRepository.Status.ACTIVE.name().equals(row.membershipStatus())) {
            throw new ApiException(ErrorCode.MEMBERSHIP_SUSPENDED, "Your access to this business is suspended");
        }
        return access(userId, row);
    }

    private BusinessAccess access(UUID userId, MembershipRow row) {
        UUID businessId = row.businessId();
        Role role = role(row);
        if (role == Role.OWNER) {
            return new BusinessAccess(businessId, row.membershipId(), userId, role,
                    EnumSet.allOf(Permission.class), GodownScope.ALL);
        }
        return new BusinessAccess(businessId, row.membershipId(), userId, role,
                grantedPermissions(businessId, row.membershipId()), godownScope(businessId, row.membershipId()));
    }

    /**
     * The current access of a membership, for background work that acts on a member's behalf and
     * must re-check it: empty when the membership is suspended or the business is not active.
     */
    @Transactional(readOnly = true)
    public Optional<BusinessAccess> resolveMembership(UUID businessId, UUID membershipId) {
        return repository.findMembershipById(businessId, membershipId)
                .filter(found -> MembershipRepository.Status.ACTIVE.name().equals(found.getValue().membershipStatus()))
                .map(found -> access(found.getKey(), found.getValue()));
    }

    /** The godowns assigned to a worker membership, sorted. */
    @Transactional(readOnly = true)
    public GodownScope godownScope(UUID businessId, UUID membershipId) {
        return new GodownScope(false, repository.findGodownIds(businessId, membershipId));
    }

    /** Permissions granted to a worker membership; unknown stored codes are ignored. */
    @Transactional(readOnly = true)
    public Set<Permission> grantedPermissions(UUID businessId, UUID membershipId) {
        Set<Permission> granted = EnumSet.noneOf(Permission.class);
        for (String code : repository.findPermissionCodes(businessId, membershipId)) {
            try {
                granted.add(Permission.valueOf(code));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring unknown permission {} granted to membership {}", code, membershipId);
            }
        }
        return granted;
    }

    private static Role role(MembershipRow row) {
        return row.owner() ? Role.OWNER : Role.WORKER;
    }
}
