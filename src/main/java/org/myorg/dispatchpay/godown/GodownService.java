package org.myorg.dispatchpay.godown;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.myorg.dispatchpay.audit.AuditChange;
import org.myorg.dispatchpay.audit.AuditEvent;
import org.myorg.dispatchpay.audit.AuditWriter;
import org.myorg.dispatchpay.godown.GodownRepository.GodownRow;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.Permission;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-owned godowns. Members see the godowns in their scope; creating and editing needs
 * {@link Permission#GODOWN_MANAGE}, and editing also needs the godown to be in scope. Edits are
 * versioned and audited.
 */
@Service
public class GodownService {

    private static final String RESOURCE = "godown";

    public record GodownView(UUID id, String name, Instant createdAt, Instant updatedAt, long version) {
    }

    private final GodownRepository godowns;
    private final AuditWriter audit;
    private final Clock clock;

    public GodownService(GodownRepository godowns, AuditWriter audit, Clock clock) {
        this.godowns = godowns;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<GodownView> list(BusinessAccess access, PageQuery page) {
        boolean all = access.godownScope().all();
        List<GodownView> items = godowns.findPage(access.businessId(), all, access.membershipId(), page.offset(),
                        page.size()).stream()
                .map(GodownService::view)
                .toList();
        return PageResponse.of(items, page, godowns.count(access.businessId(), all, access.membershipId()));
    }

    /** Runs inside the idempotent command's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public GodownView create(BusinessAccess access, String name) {
        access.require(Permission.GODOWN_MANAGE);
        UUID id = UUID.randomUUID();
        requireUniqueName(access.businessId(), name, id);
        Instant now = clock.instant();
        try {
            godowns.insert(id, access.businessId(), name, now);
        } catch (DuplicateKeyException concurrentCreate) {
            throw nameConflict();
        }
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "godown.create", RESOURCE, id,
                Map.of("name", new AuditChange(null, name))));
        return new GodownView(id, name, now, now, 0);
    }

    /**
     * Renames a godown last read at {@code expectedVersion}. A godown of another business is not
     * found; one of this business outside the caller's scope is denied. Runs inside the idempotent
     * command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public GodownView update(BusinessAccess access, UUID id, String name, long expectedVersion) {
        access.require(Permission.GODOWN_MANAGE);
        GodownRow godown = godowns.lock(access.businessId(), id).orElseThrow(GodownService::notFound);
        access.requireGodown(id);
        if (godown.version() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "This godown was changed by another request; reload and retry");
        }
        if (!godown.name().equals(name)) {
            requireUniqueName(access.businessId(), name, id);
            try {
                godowns.rename(id, name, clock.instant());
            } catch (DuplicateKeyException concurrentRename) {
                throw nameConflict();
            }
            audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "godown.update", RESOURCE, id,
                    Map.of("name", new AuditChange(godown.name(), name))));
        }
        return view(godowns.find(access.businessId(), id).orElseThrow(GodownService::notFound));
    }

    private void requireUniqueName(UUID businessId, String name, UUID exceptId) {
        if (godowns.nameTaken(businessId, name, exceptId)) {
            throw nameConflict();
        }
    }

    private static GodownView view(GodownRow row) {
        return new GodownView(row.id(), row.name(), row.createdAt(), row.updatedAt(), row.version());
    }

    private static ApiException nameConflict() {
        return new ApiException(ErrorCode.CONFLICT, "A godown with this name already exists");
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Godown not found");
    }
}
