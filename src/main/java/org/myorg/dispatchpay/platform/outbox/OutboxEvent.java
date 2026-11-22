package org.myorg.dispatchpay.platform.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * An event as delivered to handlers. {@code payload} and {@code metadata} are JSON;
 * {@code metadata} carries attribution (actor, initiator, approver) once IAM exists.
 * Delivery is at-least-once, so handlers must use {@link #id()} for de-duplication.
 */
public record OutboxEvent(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        UUID tenantId,
        String correlationId,
        String metadata,
        int attempts,
        Instant createdAt) {
}
