package org.myorg.dispatchpay.iam.worker;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.myorg.dispatchpay.audit.AuditChange;
import org.myorg.dispatchpay.audit.AuditEvent;
import org.myorg.dispatchpay.audit.AuditWriter;
import org.myorg.dispatchpay.iam.access.AccessService;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.BusinessAccess.GodownScope;
import org.myorg.dispatchpay.iam.access.Permission;
import org.myorg.dispatchpay.iam.membership.MembershipRepository;
import org.myorg.dispatchpay.iam.worker.WorkerRepository.WorkerRow;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-only management of the business's workers. Only membership facts and the mobile the owner
 * invited are exposed: never the global user id, other businesses, sessions or devices.
 *
 * <p>Access is evaluated from the database on every request (there is no permission cache), so a
 * committed change applies to the worker's next request. Every change is audited in its transaction.
 */
@Service
public class WorkerService {

    private static final String RESOURCE = "worker";
    private static final String ACTIVE = MembershipRepository.Status.ACTIVE.name();
    private static final String SUSPENDED = MembershipRepository.Status.SUSPENDED.name();

    public record WorkerSummary(UUID membershipId, String mobile, String status, Instant joinedAt) {
    }

    /** @param version authorization version; send it back to change this worker's access */
    public record WorkerDetail(
            UUID membershipId,
            String mobile,
            String status,
            Instant joinedAt,
            List<Permission> permissions,
            GodownScope godownScope,
            long version) {
    }

    private final WorkerRepository workers;
    private final AccessService accessService;
    private final AuditWriter audit;
    private final Clock clock;

    public WorkerService(WorkerRepository workers, AccessService accessService, AuditWriter audit, Clock clock) {
        this.workers = workers;
        this.accessService = accessService;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkerSummary> list(BusinessAccess access, PageQuery page) {
        access.requireOwner();
        List<WorkerSummary> items = workers.findPage(access.businessId(), page.offset(), page.size()).stream()
                .map(row -> new WorkerSummary(row.membershipId(), row.mobile(), row.status(), row.joinedAt()))
                .toList();
        return PageResponse.of(items, page, workers.count(access.businessId()));
    }

    /** A membership of another business, or the owner's own, is reported exactly like an unknown id. */
    @Transactional(readOnly = true)
    public WorkerDetail get(BusinessAccess access, UUID membershipId) {
        access.requireOwner();
        WorkerRow row = workers.find(access.businessId(), membershipId).orElseThrow(WorkerService::notFound);
        return detail(access.businessId(), row);
    }

    /**
     * Replaces the worker's grants with exactly {@code permissions}, provided the worker's access has
     * not changed since {@code expectedVersion}. Runs inside the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkerDetail replacePermissions(BusinessAccess access, UUID membershipId, long expectedVersion,
            Set<Permission> permissions) {
        WorkerRow worker = lockForChange(access, membershipId);
        requireVersion(worker, expectedVersion);
        Instant now = clock.instant();
        List<String> before = grantNames(access.businessId(), membershipId);
        workers.replacePermissions(access.businessId(), membershipId, permissions, now);
        workers.updateAccess(membershipId, worker.status(), now);
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "iam.worker.permissions.replace",
                RESOURCE, membershipId, Map.of("permissions", new AuditChange(before, names(permissions)))));
        return reload(access.businessId(), membershipId);
    }

    /**
     * Suspends the worker's membership in this business only; the user's identity, sessions and other
     * memberships are untouched. Every later request by the worker in this business, including the
     * confirmation of anything prepared earlier, re-resolves access and is refused. Suspending an
     * already suspended worker changes nothing.
     */
    @Transactional
    public WorkerDetail suspend(BusinessAccess access, UUID membershipId) {
        WorkerRow worker = lockForChange(access, membershipId);
        if (!SUSPENDED.equals(worker.status())) {
            workers.updateAccess(membershipId, SUSPENDED, clock.instant());
            audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "iam.worker.suspend",
                    RESOURCE, membershipId, Map.of("status", new AuditChange(worker.status(), SUSPENDED))));
        }
        return reload(access.businessId(), membershipId);
    }

    /**
     * Reactivates a suspended worker with an explicit set of grants, reviewed by the owner: the
     * grants held before suspension are kept only if they are listed again. Runs inside the
     * idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkerDetail reactivate(BusinessAccess access, UUID membershipId, long expectedVersion,
            Set<Permission> permissions) {
        WorkerRow worker = lockForChange(access, membershipId);
        requireVersion(worker, expectedVersion);
        if (!SUSPENDED.equals(worker.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "Only a suspended worker can be reactivated");
        }
        Instant now = clock.instant();
        List<String> before = grantNames(access.businessId(), membershipId);
        workers.replacePermissions(access.businessId(), membershipId, permissions, now);
        workers.updateAccess(membershipId, ACTIVE, now);
        Map<String, AuditChange> changes = new LinkedHashMap<>();
        changes.put("status", new AuditChange(SUSPENDED, ACTIVE));
        changes.put("permissions", new AuditChange(before, names(permissions)));
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "iam.worker.reactivate",
                RESOURCE, membershipId, changes));
        return reload(access.businessId(), membershipId);
    }

    private static void requireVersion(WorkerRow worker, long expectedVersion) {
        if (worker.version() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "This worker's access was changed by another request; reload and retry");
        }
    }

    private List<String> grantNames(UUID businessId, UUID membershipId) {
        return names(accessService.grantedPermissions(businessId, membershipId));
    }

    private static List<String> names(Set<Permission> permissions) {
        return permissions.stream().sorted().map(Enum::name).toList();
    }

    /** Serializes access changes per business and re-checks ownership under the lock. */
    private WorkerRow lockForChange(BusinessAccess access, UUID membershipId) {
        access.requireOwner();
        UUID owner = workers.lockBusinessOwner(access.businessId()).orElseThrow(WorkerService::notFound);
        if (!owner.equals(access.membershipId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the business owner can perform this action");
        }
        return workers.lock(access.businessId(), membershipId).orElseThrow(WorkerService::notFound);
    }

    private WorkerDetail reload(UUID businessId, UUID membershipId) {
        return detail(businessId, workers.find(businessId, membershipId).orElseThrow(WorkerService::notFound));
    }

    private WorkerDetail detail(UUID businessId, WorkerRow row) {
        List<Permission> permissions = accessService.grantedPermissions(businessId, row.membershipId()).stream()
                .sorted()
                .toList();
        // Godown assignments arrive with M01.S10.
        return new WorkerDetail(row.membershipId(), row.mobile(), row.status(), row.joinedAt(), permissions,
                GodownScope.NONE, row.version());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Worker not found");
    }
}
