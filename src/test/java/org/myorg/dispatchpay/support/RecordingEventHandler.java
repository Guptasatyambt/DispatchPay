package org.myorg.dispatchpay.support;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.myorg.dispatchpay.platform.outbox.OutboxEvent;
import org.myorg.dispatchpay.platform.outbox.OutboxEventHandler;
import org.myorg.dispatchpay.platform.web.CorrelationId;
import org.springframework.stereotype.Component;

/** Test handler that counts deliveries per event and fails on {@code test.fail}. */
@Component
public class RecordingEventHandler implements OutboxEventHandler {

    public static final String FAILING_EVENT = "test.fail";

    private final Map<UUID, AtomicInteger> deliveries = new ConcurrentHashMap<>();
    private final Map<UUID, String> correlationIds = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "test-recorder";
    }

    @Override
    public boolean supports(String eventType) {
        return eventType.startsWith("test.");
    }

    @Override
    public void handle(OutboxEvent event) {
        if (FAILING_EVENT.equals(event.eventType())) {
            throw new IllegalStateException("simulated handler failure");
        }
        deliveries.computeIfAbsent(event.id(), id -> new AtomicInteger()).incrementAndGet();
        CorrelationId.current().ifPresent(value -> correlationIds.put(event.id(), value));
    }

    public int deliveries(UUID eventId) {
        AtomicInteger count = deliveries.get(eventId);
        return count == null ? 0 : count.get();
    }

    public int totalDeliveries() {
        return deliveries.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public Map<UUID, AtomicInteger> deliveriesByEvent() {
        return deliveries;
    }

    public String correlationIdSeenFor(UUID eventId) {
        return correlationIds.get(eventId);
    }

    public void reset() {
        deliveries.clear();
        correlationIds.clear();
    }
}
