package org.myorg.dispatchpay.platform.idempotency;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyRepository repository;
    private final Clock clock;

    IdempotencyCleanupJob(IdempotencyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${dispatchpay.idempotency.cleanup-interval}",
            initialDelayString = "${dispatchpay.idempotency.cleanup-interval}")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteAllExpired(clock.instant());
        if (deleted > 0) {
            log.info("Purged {} expired idempotency records", deleted);
        }
    }
}
