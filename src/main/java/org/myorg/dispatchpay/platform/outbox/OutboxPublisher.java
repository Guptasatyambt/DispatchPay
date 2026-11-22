package org.myorg.dispatchpay.platform.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.myorg.dispatchpay.platform.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polling outbox worker. Each event is claimed with {@code FOR UPDATE SKIP LOCKED} and delivered
 * in its own transaction, so any number of application instances can poll concurrently and a
 * crash at any point either commits the delivery fully or leaves the event pending for replay.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final OutboxRepository repository;
    private final OutboxDispatcher dispatcher;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OutboxPublisher(OutboxRepository repository, OutboxDispatcher dispatcher, OutboxProperties properties,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${dispatchpay.outbox.poll-interval}")
    public void poll() {
        publishBatch();
    }

    /** Delivers up to one batch of due events and returns how many were attempted. */
    public int publishBatch() {
        int attempted = 0;
        while (attempted < properties.batchSize() && publishNext()) {
            attempted++;
        }
        return attempted;
    }

    private boolean publishNext() {
        OutboxEvent[] claimed = new OutboxEvent[1];
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                Optional<OutboxEvent> next = repository.lockNextDue(clock.instant());
                if (next.isEmpty()) {
                    return false;
                }
                OutboxEvent event = next.get();
                claimed[0] = event;
                withCorrelationId(event, () -> dispatcher.dispatch(event));
                repository.markPublished(event.id(), clock.instant());
                return true;
            }));
        } catch (RuntimeException e) {
            if (claimed[0] == null) {
                throw e;
            }
            recordFailure(claimed[0], e);
            return true;
        }
    }

    private void recordFailure(OutboxEvent event, RuntimeException error) {
        int attempts = event.attempts() + 1;
        boolean dead = attempts >= properties.maxAttempts();
        Instant nextAttemptAt = clock.instant().plus(backoff(attempts));
        String message = truncate(error.getClass().getName() + ": " + error.getMessage());
        if (dead) {
            log.error("Outbox event {} ({}) parked as DEAD after {} attempts",
                    event.id(), event.eventType(), attempts, error);
        } else {
            log.warn("Outbox event {} ({}) failed attempt {}; retrying at {}",
                    event.id(), event.eventType(), attempts, nextAttemptAt, error);
        }
        transactionTemplate.executeWithoutResult(status ->
                repository.markFailed(event.id(), attempts, dead, nextAttemptAt, message));
    }

    Duration backoff(int attempts) {
        Duration max = properties.maxBackoff();
        Duration delay = properties.initialBackoff();
        for (int i = 1; i < attempts && delay.compareTo(max) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(max) > 0 ? max : delay;
    }

    private static void withCorrelationId(OutboxEvent event, Runnable action) {
        String previous = MDC.get(CorrelationId.MDC_KEY);
        if (event.correlationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        }
        try {
            action.run();
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationId.MDC_KEY);
            } else {
                MDC.put(CorrelationId.MDC_KEY, previous);
            }
        }
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
