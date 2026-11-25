package org.myorg.dispatchpay.audit;

import java.time.Clock;
import java.util.UUID;

import org.myorg.dispatchpay.platform.web.CorrelationId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends audit events in the caller's mutation transaction and fails if there is none, so an
 * event exists exactly when its mutation commits. The request's correlation id is attached.
 */
@Component
public class AuditWriter {

    private final AuditRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public AuditWriter(AuditRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(AuditEvent event) {
        UUID id = UUID.randomUUID();
        repository.insert(id, event, jsonMapper.writeValueAsString(AuditRedaction.redact(event.changes())),
                CorrelationId.current().orElse(null), clock.instant());
        return id;
    }
}
