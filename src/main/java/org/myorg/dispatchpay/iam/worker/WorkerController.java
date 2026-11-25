package org.myorg.dispatchpay.iam.worker;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.Permission;
import org.myorg.dispatchpay.iam.worker.WorkerService.WorkerDetail;
import org.myorg.dispatchpay.iam.worker.WorkerService.WorkerSummary;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.idempotency.IdempotentCommandExecutor;
import org.myorg.dispatchpay.platform.idempotency.IdempotentRequest;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-only management of the selected business's workers. Replacing permissions and reactivating require an
 * {@code Idempotency-Key} and the worker's current {@code version}; suspension is naturally
 * idempotent and needs neither.
 */
@RestController
@RequestMapping("/v1/iam/workers")
public class WorkerController {

    /**
     * @param permissions the complete set of grants after the change; must come from the catalog
     * @param version     the worker's authorization version as last read
     */
    public record GrantsRequest(
            @NotNull @Size(max = 100) List<@NotNull @Size(max = 64) String> permissions,
            @NotNull @PositiveOrZero Long version) {
    }

    /** Idempotency payload: the business and worker are part of the content. */
    record GrantsPayload(UUID businessId, UUID membershipId, List<Permission> permissions, long version) {
    }

    private interface GrantsCommand {
        WorkerDetail apply(BusinessAccess access, UUID membershipId, long version, Set<Permission> permissions);
    }

    /**
     * @param godownIds the complete set of godowns after the change; each must belong to this business
     * @param version   the worker's authorization version as last read
     */
    public record GodownsRequest(
            @NotNull @Size(max = 500) List<@NotNull UUID> godownIds,
            @NotNull @PositiveOrZero Long version) {
    }

    record GodownsPayload(UUID businessId, UUID membershipId, List<UUID> godownIds, long version) {
    }

    private final WorkerService workerService;
    private final IdempotentCommandExecutor idempotentExecutor;

    public WorkerController(WorkerService workerService, IdempotentCommandExecutor idempotentExecutor) {
        this.workerService = workerService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @GetMapping
    PageResponse<WorkerSummary> list(BusinessAccess access, PageQuery page) {
        return workerService.list(access, page);
    }

    @GetMapping("/{id}")
    WorkerDetail get(BusinessAccess access, @PathVariable String id) {
        return workerService.get(access, parse(id));
    }

    @PutMapping("/{id}/permissions")
    ResponseEntity<WorkerDetail> replacePermissions(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @Valid @RequestBody GrantsRequest body,
            BusinessAccess access) {
        return applyGrants("iam.workers.permissions.replace", idempotencyKey, id, body, access,
                workerService::replacePermissions);
    }

    @PutMapping("/{id}/godowns")
    ResponseEntity<WorkerDetail> replaceGodowns(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @Valid @RequestBody GodownsRequest body,
            BusinessAccess access) {
        access.requireOwner();
        UUID membershipId = parse(id);
        Set<UUID> godownIds = new TreeSet<>(body.godownIds());
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "iam.workers.godowns.replace",
                idempotencyKey, new GodownsPayload(access.businessId(), membershipId, List.copyOf(godownIds),
                        body.version()));
        return idempotentExecutor
                .execute(request, WorkerDetail.class,
                        () -> workerService.replaceGodowns(access, membershipId, body.version(), godownIds))
                .toResponse(HttpStatus.OK);
    }

    @PostMapping("/{id}/suspend")
    WorkerDetail suspend(BusinessAccess access, @PathVariable String id) {
        return workerService.suspend(access, parse(id));
    }

    /** The body lists the grants the worker returns with; nothing held before suspension is kept implicitly. */
    @PostMapping("/{id}/reactivate")
    ResponseEntity<WorkerDetail> reactivate(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @Valid @RequestBody GrantsRequest body,
            BusinessAccess access) {
        return applyGrants("iam.workers.reactivate", idempotencyKey, id, body, access, workerService::reactivate);
    }

    private ResponseEntity<WorkerDetail> applyGrants(String operation, String idempotencyKey, String id,
            GrantsRequest body, BusinessAccess access, GrantsCommand command) {
        access.requireOwner();
        UUID membershipId = parse(id);
        Set<Permission> permissions = allowlisted(body.permissions());
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), operation, idempotencyKey,
                new GrantsPayload(access.businessId(), membershipId, List.copyOf(permissions), body.version()));
        return idempotentExecutor
                .execute(request, WorkerDetail.class,
                        () -> command.apply(access, membershipId, body.version(), permissions))
                .toResponse(HttpStatus.OK);
    }

    /** Only catalog permissions can be granted; anything else rejects the whole request. */
    private static Set<Permission> allowlisted(List<String> codes) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        List<String> unknown = new ArrayList<>();
        for (String code : codes) {
            try {
                permissions.add(Permission.valueOf(code));
            } catch (IllegalArgumentException e) {
                unknown.add(code);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown permissions: " + String.join(", ", unknown));
        }
        return permissions;
    }

    /** A malformed id is just another id that does not exist here. */
    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Worker not found");
        }
    }
}
