package org.myorg.dispatchpay.iam.worker;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Non-owner memberships of one business. Every query is filtered by the business. */
@Repository
class WorkerRepository {

    record WorkerRow(UUID membershipId, String mobile, String status, Instant joinedAt) {
    }

    private static final String WORKER_FROM = """
            FROM business_membership m
            JOIN business b ON b.id = m.business_id
            JOIN user_account u ON u.id = m.user_id
            WHERE m.business_id = :businessId AND m.id <> b.owner_membership_id
            """;

    private final JdbcClient jdbc;

    WorkerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<WorkerRow> findPage(UUID businessId, long offset, int limit) {
        return jdbc.sql("SELECT m.id, u.mobile, m.status, m.created_at " + WORKER_FROM
                        + " ORDER BY m.created_at, m.id LIMIT :limit OFFSET :offset")
                .param("businessId", businessId)
                .param("limit", limit)
                .param("offset", offset)
                .query(WorkerRepository::map)
                .list();
    }

    long count(UUID businessId) {
        return jdbc.sql("SELECT count(*) " + WORKER_FROM)
                .param("businessId", businessId)
                .query(Long.class)
                .single();
    }

    Optional<WorkerRow> find(UUID businessId, UUID membershipId) {
        return jdbc.sql("SELECT m.id, u.mobile, m.status, m.created_at " + WORKER_FROM + " AND m.id = :id")
                .param("businessId", businessId)
                .param("id", membershipId)
                .query(WorkerRepository::map)
                .optional();
    }

    private static WorkerRow map(ResultSet rs, int rowNum) throws SQLException {
        return new WorkerRow(rs.getObject("id", UUID.class), rs.getString("mobile"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }
}
