package org.myorg.dispatchpay.iam.worker;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.myorg.dispatchpay.iam.access.AccessService;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.BusinessAccess.GodownScope;
import org.myorg.dispatchpay.iam.access.Permission;
import org.myorg.dispatchpay.iam.worker.WorkerRepository.WorkerRow;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-only view of the business's workers. Only membership facts and the mobile the owner
 * invited are exposed: never the global user id, other businesses, sessions or devices.
 */
@Service
public class WorkerService {

    public record WorkerSummary(UUID membershipId, String mobile, String status, Instant joinedAt) {
    }

    public record WorkerDetail(
            UUID membershipId,
            String mobile,
            String status,
            Instant joinedAt,
            List<Permission> permissions,
            GodownScope godownScope) {
    }

    private final WorkerRepository workers;
    private final AccessService accessService;

    public WorkerService(WorkerRepository workers, AccessService accessService) {
        this.workers = workers;
        this.accessService = accessService;
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
        WorkerRow row = workers.find(access.businessId(), membershipId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Worker not found"));
        List<Permission> permissions = accessService.grantedPermissions(access.businessId(), membershipId).stream()
                .sorted()
                .toList();
        // Godown assignments arrive with M01.S10.
        return new WorkerDetail(row.membershipId(), row.mobile(), row.status(), row.joinedAt(), permissions,
                GodownScope.NONE);
    }
}
