package org.myorg.dispatchpay.iam.membership;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A user's access to one business. Whether it is the owner follows from the business row. */
@Repository
public class MembershipRepository {

    public enum Status {
        ACTIVE, SUSPENDED
    }

    private final JdbcClient jdbc;

    public MembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UUID id, UUID businessId, UUID userId, Status status, Instant now) {
        jdbc.sql("""
                        INSERT INTO business_membership (id, business_id, user_id, status, created_at, updated_at)
                        VALUES (:id, :businessId, :userId, :status, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("userId", userId)
                .param("status", status.name())
                .param("now", Timestamp.from(now))
                .update();
    }
}
