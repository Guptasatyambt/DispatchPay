package org.myorg.dispatchpay.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.myorg.dispatchpay.audit.AuditRepository.AuditRow;
import org.myorg.dispatchpay.audit.AuditRepository.Filter;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.Permission;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Read-only audit history of the selected business, for holders of {@link Permission#AUDIT_READ}. */
@Service
public class AuditHistoryService {

    private static final TypeReference<Map<String, AuditChange>> CHANGES = new TypeReference<>() {
    };

    public record AuditEventView(
            UUID id,
            Instant occurredAt,
            String actorType,
            UUID actorMembershipId,
            UUID initiatorMembershipId,
            UUID approverMembershipId,
            String source,
            String action,
            String resourceType,
            String resourceId,
            Map<String, AuditChange> changes,
            String correlationId) {
    }

    private final AuditRepository repository;
    private final JsonMapper jsonMapper;

    public AuditHistoryService(AuditRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEventView> find(BusinessAccess access, String resourceType, String resourceId,
            Instant from, Instant to, PageQuery page) {
        access.require(Permission.AUDIT_READ);
        Filter filter = new Filter(access.businessId(), resourceType, resourceId, from, to);
        List<AuditEventView> items = repository.findPage(filter, page.offset(), page.size()).stream()
                .map(this::view)
                .toList();
        return PageResponse.of(items, page, repository.count(filter));
    }

    private AuditEventView view(AuditRow row) {
        Map<String, AuditChange> changes = AuditRedaction.redact(jsonMapper.readValue(row.changes(), CHANGES));
        return new AuditEventView(row.id(), row.occurredAt(), row.actorType(), row.actorMembershipId(),
                row.initiatorMembershipId(), row.approverMembershipId(), row.source(), row.action(),
                row.resourceType(), row.resourceId(), changes, row.correlationId());
    }
}
