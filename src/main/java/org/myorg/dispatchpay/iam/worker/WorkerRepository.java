package org.myorg.dispatchpay.iam.worker;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.myorg.dispatchpay.iam.access.Permission;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Non-owner memberships of one business. Every query is filtered by the business. The membership's
 * {@code version} is its authorization version: every access change increments it.
 */
@Repository
class WorkerRepository {

    record WorkerRow(UUID membershipId, String mobile, String status, Instant joinedAt, long version) {
    }

    private static final String WORKER_SELECT = """
            SELECT m.id, u.mobile, m.status, m.created_at, m.version
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
        return jdbc.sql(WORKER_SELECT + " ORDER BY m.created_at, m.id LIMIT :limit OFFSET :offset")
                .param("businessId", businessId)
                .param("limit", limit)
                .param("offset", offset)
                .query(WorkerRepository::map)
                .list();
    }

    long count(UUID businessId) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM business_membership m JOIN business b ON b.id = m.business_id
                        WHERE m.business_id = :businessId AND m.id <> b.owner_membership_id
                        """)
                .param("businessId", businessId)
                .query(Long.class)
                .single();
    }

    Optional<WorkerRow> find(UUID businessId, UUID membershipId) {
        return jdbc.sql(WORKER_SELECT + " AND m.id = :id")
                .param("businessId", businessId)
                .param("id", membershipId)
                .query(WorkerRepository::map)
                .optional();
    }

    /**
     * Locks the active business and returns its owner membership. {@code FOR NO KEY UPDATE} so
     * foreign-key checks from other tables are not blocked.
     */
    Optional<UUID> lockBusinessOwner(UUID businessId) {
        return jdbc.sql("""
                        SELECT owner_membership_id FROM business
                        WHERE id = :id AND status = 'ACTIVE'
                        FOR NO KEY UPDATE
                        """)
                .param("id", businessId)
                .query(UUID.class)
                .optional();
    }

    /** Locks a worker membership for an access change; the caller holds the business lock. */
    Optional<WorkerRow> lock(UUID businessId, UUID membershipId) {
        return jdbc.sql(WORKER_SELECT + " AND m.id = :id FOR UPDATE OF m")
                .param("businessId", businessId)
                .param("id", membershipId)
                .query(WorkerRepository::map)
                .optional();
    }

    void replacePermissions(UUID businessId, UUID membershipId, Set<Permission> permissions, Instant now) {
        jdbc.sql("DELETE FROM membership_permission WHERE business_id = :businessId AND membership_id = :id")
                .param("businessId", businessId)
                .param("id", membershipId)
                .update();
        for (Permission permission : permissions) {
            jdbc.sql("""
                            INSERT INTO membership_permission (business_id, membership_id, permission, created_at)
                            VALUES (:businessId, :id, :permission, :now)
                            """)
                    .param("businessId", businessId)
                    .param("id", membershipId)
                    .param("permission", permission.name())
                    .param("now", Timestamp.from(now))
                    .update();
        }
    }

    /** The subset of {@code godownIds} that are godowns of this business. */
    List<UUID> findGodownIds(UUID businessId, Set<UUID> godownIds) {
        if (godownIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id FROM godown WHERE business_id = :businessId AND id IN (:ids)")
                .param("businessId", businessId)
                .param("ids", godownIds)
                .query(UUID.class)
                .list();
    }

    void replaceGodowns(UUID businessId, UUID membershipId, Set<UUID> godownIds, Instant now) {
        jdbc.sql("DELETE FROM membership_godown WHERE business_id = :businessId AND membership_id = :id")
                .param("businessId", businessId)
                .param("id", membershipId)
                .update();
        for (UUID godownId : godownIds) {
            jdbc.sql("""
                            INSERT INTO membership_godown (business_id, membership_id, godown_id, created_at)
                            VALUES (:businessId, :id, :godownId, :now)
                            """)
                    .param("businessId", businessId)
                    .param("id", membershipId)
                    .param("godownId", godownId)
                    .param("now", Timestamp.from(now))
                    .update();
        }
    }

    /** Sets the status and increments the authorization version. */
    void updateAccess(UUID membershipId, String status, Instant now) {
        jdbc.sql("""
                        UPDATE business_membership
                        SET status = :status, version = version + 1, updated_at = :now
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("now", Timestamp.from(now))
                .param("id", membershipId)
                .update();
    }

    private static WorkerRow map(ResultSet rs, int rowNum) throws SQLException {
        return new WorkerRow(rs.getObject("id", UUID.class), rs.getString("mobile"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("version"));
    }
}
