package org.myorg.dispatchpay.platform.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Readiness contributor {@code objectStorage}: imports and file downloads need the bucket. */
class ObjectStorageHealthIndicator implements HealthIndicator {

    private final ObjectStorage storage;

    ObjectStorageHealthIndicator(ObjectStorage storage) {
        this.storage = storage;
    }

    @Override
    public Health health() {
        return storage.isAvailable() ? Health.up().build() : Health.down().build();
    }
}
