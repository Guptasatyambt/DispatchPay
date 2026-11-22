package org.myorg.dispatchpay.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.myorg.dispatchpay.IntegrationTest;
import org.myorg.dispatchpay.platform.web.CorrelationId;
import org.myorg.dispatchpay.support.RecordingEventHandler;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** M00.S02 gate: publisher crash/replay causes no duplicate effects. */
class OutboxIT extends IntegrationTest {

    @Autowired
    OutboxWriter writer;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void appendRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> writer.append("widget", "1", "test.created", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rolledBackBusinessTransactionLeavesNoEvent() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            writer.append("widget", "1", "test.created", Map.of("n", 1));
            status.setRollbackOnly();
        });

        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void publishesCommittedEventOnceAndPropagatesCorrelationId() {
        MDC.put(CorrelationId.MDC_KEY, "corr-outbox-1");
        UUID eventId;
        try {
            eventId = appendCommitted("test.created");
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }

        assertThat(publisher.publishBatch()).isEqualTo(1);

        assertThat(recordingHandler.deliveries(eventId)).isEqualTo(1);
        assertThat(recordingHandler.correlationIdSeenFor(eventId)).isEqualTo("corr-outbox-1");
        assertThat(statusOf(eventId)).isEqualTo("PUBLISHED");
        assertThat(publisher.publishBatch()).isZero();
    }

    @Test
    void redeliveryAfterCrashDoesNotDuplicateHandlerEffects() {
        UUID eventId = appendCommitted("test.created");
        publisher.publishBatch();

        // Simulate the publisher crashing after the handler committed but before the event was
        // marked published: the event becomes due again and is redelivered.
        jdbc.sql("UPDATE outbox_event SET status = 'PENDING', published_at = NULL WHERE id = :id")
                .param("id", eventId).update();
        assertThat(publisher.publishBatch()).isEqualTo(1);

        assertThat(recordingHandler.deliveries(eventId)).isEqualTo(1);
        assertThat(statusOf(eventId)).isEqualTo("PUBLISHED");
    }

    @Test
    void failingHandlerIsRetriedWithBackoffThenParkedAsDead() {
        UUID eventId = appendCommitted(RecordingEventHandler.FAILING_EVENT);

        publisher.publishBatch();
        assertThat(statusOf(eventId)).isEqualTo("PENDING");
        assertThat(attemptsOf(eventId)).isEqualTo(1);
        assertThat(lastErrorOf(eventId)).contains("simulated handler failure");
        assertThat(publisher.publishBatch()).as("not due until backoff elapses").isZero();
        assertThat(count("processed_event")).as("failed delivery leaves no marker").isZero();

        makeDue(eventId);
        publisher.publishBatch();
        makeDue(eventId);
        publisher.publishBatch();

        assertThat(attemptsOf(eventId)).isEqualTo(3);
        assertThat(statusOf(eventId)).isEqualTo("DEAD");
        makeDue(eventId);
        assertThat(publisher.publishBatch()).isZero();
    }

    @Test
    void oneFailingEventDoesNotBlockOthers() {
        UUID failing = appendCommitted(RecordingEventHandler.FAILING_EVENT);
        UUID healthy = appendCommitted("test.created");

        publisher.publishBatch();

        assertThat(statusOf(failing)).isEqualTo("PENDING");
        assertThat(statusOf(healthy)).isEqualTo("PUBLISHED");
    }

    @Test
    void concurrentPublishersDeliverEachEventExactlyOnce() throws Exception {
        int events = 40;
        for (int i = 0; i < events; i++) {
            appendCommitted("test.created");
        }
        int workers = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    int total = 0;
                    int published;
                    while ((published = publisher.publishBatch()) > 0) {
                        total += published;
                    }
                    return total;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> future : futures) {
                total += future.get();
            }

            assertThat(total).isEqualTo(events);
        }
        assertThat(recordingHandler.deliveriesByEvent()).hasSize(events);
        assertThat(recordingHandler.totalDeliveries()).isEqualTo(events);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE status = 'PUBLISHED'")
                .query(Long.class).single()).isEqualTo(events);
    }

    @Test
    void backoffDoublesAndIsCapped() {
        OutboxProperties properties = new OutboxProperties(Duration.ofSeconds(1), 10, 10,
                Duration.ofSeconds(2), Duration.ofSeconds(30));
        OutboxPublisher calculator = new OutboxPublisher(null, null, properties, transactionManager, null);

        assertThat(calculator.backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(calculator.backoff(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(calculator.backoff(4)).isEqualTo(Duration.ofSeconds(16));
        assertThat(calculator.backoff(5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(calculator.backoff(500)).isEqualTo(Duration.ofSeconds(30));
    }

    private UUID appendCommitted(String eventType) {
        return new TransactionTemplate(transactionManager).execute(status ->
                writer.append("widget", UUID.randomUUID().toString(), eventType, Map.of("qty", 1)));
    }

    private void makeDue(UUID id) {
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = now() WHERE id = :id").param("id", id).update();
    }

    private String statusOf(UUID id) {
        return jdbc.sql("SELECT status FROM outbox_event WHERE id = :id").param("id", id).query(String.class).single();
    }

    private int attemptsOf(UUID id) {
        return jdbc.sql("SELECT attempts FROM outbox_event WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    private String lastErrorOf(UUID id) {
        return jdbc.sql("SELECT last_error FROM outbox_event WHERE id = :id").param("id", id).query(String.class).single();
    }
}
