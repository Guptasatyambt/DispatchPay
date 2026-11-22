package org.myorg.dispatchpay.support;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stand-in business command: writes an outbox event and counts executions. */
@Service
public class WidgetService {

    public record CreateWidget(String name, int quantity) {
    }

    public record WidgetCreated(UUID id, String name, int quantity) {
    }

    private final OutboxWriter outboxWriter;
    private final AtomicInteger executions = new AtomicInteger();
    private volatile Duration delay = Duration.ZERO;

    public WidgetService(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public WidgetCreated create(CreateWidget command) {
        executions.incrementAndGet();
        sleep();
        WidgetCreated created = new WidgetCreated(UUID.randomUUID(), command.name(), command.quantity());
        outboxWriter.append("widget", created.id().toString(), "test.widget.created", created);
        if ("fail".equals(command.name())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Widget rejected");
        }
        return created;
    }

    public int executions() {
        return executions.get();
    }

    public void delay(Duration delay) {
        this.delay = delay;
    }

    public void reset() {
        executions.set(0);
        delay = Duration.ZERO;
    }

    private void sleep() {
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
