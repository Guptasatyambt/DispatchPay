package org.myorg.dispatchpay.platform.outbox;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Delivers one event to every interested handler, skipping handlers that already applied it. */
@Component
class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final List<OutboxEventHandler> handlers;
    private final ProcessedEventRepository processedEvents;
    private final Clock clock;

    OutboxDispatcher(List<OutboxEventHandler> handlers, ProcessedEventRepository processedEvents, Clock clock) {
        this.handlers = handlers;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void dispatch(OutboxEvent event) {
        for (OutboxEventHandler handler : handlers) {
            if (!handler.supports(event.eventType())) {
                continue;
            }
            if (processedEvents.markProcessed(handler.name(), event.id(), clock.instant())) {
                handler.handle(event);
            } else {
                log.debug("Skipping duplicate delivery of event {} to {}", event.id(), handler.name());
            }
        }
    }
}
