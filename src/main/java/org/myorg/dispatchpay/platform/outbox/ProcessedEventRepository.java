package org.myorg.dispatchpay.platform.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ProcessedEventRepository {

    private final JdbcClient jdbc;

    ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns true only for the first delivery of this event to this handler. */
    boolean markProcessed(String handler, UUID eventId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO processed_event (handler, event_id, processed_at)
                        VALUES (:handler, :eventId, :now)
                        ON CONFLICT (handler, event_id) DO NOTHING
                        """)
                .param("handler", handler)
                .param("eventId", eventId)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }
}
