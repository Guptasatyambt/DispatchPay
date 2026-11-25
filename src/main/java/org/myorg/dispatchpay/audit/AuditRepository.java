package org.myorg.dispatchpay.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Insert and read only: the table refuses updates and deletes. */
@Repository
class AuditRepository {

    /** History filters within one business; null fields are not filtered on. */
    record Filter(UUID businessId, String resourceType, String resourceId, Instant from, Instant to) {
    }

    record AuditRow(
            UUID id,
            Instant occurredAt,
            String actorType,
            UUID actorMembershipId,
            UUID initiatorMembershipId,
            UUID approverMembershipId,
            String source,
            String action,
            String resourceType,
            String resourceId,
            String changes,
            String correlationId) {
    }

    private static final String WHERE = """
             WHERE business_id = :businessId
               AND (CAST(:resourceType AS VARCHAR) IS NULL OR resource_type = :resourceType)
               AND (CAST(:resourceId AS VARCHAR) IS NULL OR resource_id = :resourceId)
               AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR occurred_at >= :from)
               AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR occurred_at < :to)
            """;

    private final JdbcClient jdbc;

    AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, AuditEvent event, String changesJson, String correlationId, Instant now) {
        jdbc.sql("""
                        INSERT INTO audit_event
                            (id, business_id, occurred_at, actor_type, actor_membership_id, initiator_membership_id,
                             approver_membership_id, source, action, resource_type, resource_id, changes,
                             correlation_id)
                        VALUES (:id, :businessId, :now, :actorType, :actor, :initiator, :approver, :source, :action,
                                :resourceType, :resourceId, CAST(:changes AS jsonb), :correlationId)
                        """)
                .param("id", id)
                .param("businessId", event.businessId())
                .param("now", Timestamp.from(now))
                .param("actorType", event.actor().type().name())
                .param("actor", event.actor().membershipId())
                .param("initiator", event.initiatorMembershipId())
                .param("approver", event.approverMembershipId())
                .param("source", event.source().name())
                .param("action", event.action())
                .param("resourceType", event.resourceType())
                .param("resourceId", event.resourceId())
                .param("changes", changesJson)
                .param("correlationId", correlationId)
                .update();
    }

    /** Newest first. */
    List<AuditRow> findPage(Filter filter, long offset, int limit) {
        return bind(jdbc.sql("SELECT * FROM audit_event" + WHERE
                        + " ORDER BY occurred_at DESC, id LIMIT :limit OFFSET :offset"), filter)
                .param("limit", limit)
                .param("offset", offset)
                .query(AuditRepository::map)
                .list();
    }

    long count(Filter filter) {
        return bind(jdbc.sql("SELECT count(*) FROM audit_event" + WHERE), filter)
                .query(Long.class)
                .single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Filter filter) {
        return spec.param("businessId", filter.businessId())
                .param("resourceType", filter.resourceType())
                .param("resourceId", filter.resourceId())
                .param("from", filter.from() == null ? null : Timestamp.from(filter.from()))
                .param("to", filter.to() == null ? null : Timestamp.from(filter.to()));
    }

    private static AuditRow map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditRow(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor_type"),
                rs.getObject("actor_membership_id", UUID.class),
                rs.getObject("initiator_membership_id", UUID.class),
                rs.getObject("approver_membership_id", UUID.class),
                rs.getString("source"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("changes"),
                rs.getString("correlation_id"));
    }
}
