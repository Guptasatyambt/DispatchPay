package org.myorg.dispatchpay.iam.business;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class BusinessRepository {

    private final JdbcClient jdbc;

    BusinessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The owner membership must be inserted in the same transaction; its foreign key is checked at commit. */
    void insert(UUID id, String name, String timezone, UUID ownerMembershipId, UUID createdByUserId, Instant now) {
        jdbc.sql("""
                        INSERT INTO business
                            (id, name, timezone, status, owner_membership_id, created_by_user_id, created_at, updated_at)
                        VALUES (:id, :name, :timezone, 'ACTIVE', :ownerMembershipId, :createdBy, :now, :now)
                        """)
                .param("id", id)
                .param("name", name)
                .param("timezone", timezone)
                .param("ownerMembershipId", ownerMembershipId)
                .param("createdBy", createdByUserId)
                .param("now", Timestamp.from(now))
                .update();
    }
}
