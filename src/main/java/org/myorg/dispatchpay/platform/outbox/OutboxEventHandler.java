package org.myorg.dispatchpay.platform.outbox;

/**
 * Consumes outbox events. The handler runs inside the delivery transaction together with its
 * de-duplication marker, so its database effects are applied exactly once even though delivery
 * is at-least-once. Handlers must not make external network calls; schedule a job instead.
 */
public interface OutboxEventHandler {

    /** Stable unique name; renaming it makes already-processed events look new. */
    String name();

    boolean supports(String eventType);

    void handle(OutboxEvent event);
}
