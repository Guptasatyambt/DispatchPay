package org.myorg.dispatchpay.platform.outbox;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.myorg.dispatchpay.platform.web.CorrelationId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Appends events in the caller's business transaction; fails if there is none. */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OutboxWriter(OutboxRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, String aggregateId, String eventType, Object payload) {
        return append(aggregateType, aggregateId, eventType, payload, null, Map.of());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, String aggregateId, String eventType, Object payload,
            UUID tenantId, Map<String, ?> metadata) {
        UUID id = UUID.randomUUID();
        repository.insert(id, aggregateType, aggregateId, eventType,
                jsonMapper.writeValueAsString(payload), tenantId, CorrelationId.current().orElse(null),
                jsonMapper.writeValueAsString(metadata), clock.instant());
        return id;
    }
}
