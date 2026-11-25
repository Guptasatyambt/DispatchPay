package org.myorg.dispatchpay.iam.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AccessRepository {

    /** One of the user's memberships in an active business. */
    record MembershipRow(
            UUID membershipId,
            String membershipStatus,
            boolean owner,
            UUID businessId,
            String businessName,
            String timezone) {
    }

    private static final String MEMBERSHIP_SELECT = """
            SELECT m.id AS membership_id, m.status AS membership_status,
                   b.owner_membership_id = m.id AS owner,
                   b.id AS business_id, b.name AS business_name, b.timezone
            FROM business_membership m
            JOIN business b ON b.id = m.business_id
            WHERE m.user_id = :userId AND b.status = 'ACTIVE'
            """;

    private final JdbcClient jdbc;

    AccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<MembershipRow> findMemberships(UUID userId) {
        return jdbc.sql(MEMBERSHIP_SELECT + " ORDER BY lower(b.name), b.id")
                .param("userId", userId)
                .query(AccessRepository::map)
                .list();
    }

    Optional<MembershipRow> findMembership(UUID userId, UUID businessId) {
        return jdbc.sql(MEMBERSHIP_SELECT + " AND b.id = :businessId")
                .param("userId", userId)
                .param("businessId", businessId)
                .query(AccessRepository::map)
                .optional();
    }

    List<String> findPermissionCodes(UUID businessId, UUID membershipId) {
        return jdbc.sql("""
                        SELECT permission FROM membership_permission
                        WHERE business_id = :businessId AND membership_id = :membershipId
                        """)
                .param("businessId", businessId)
                .param("membershipId", membershipId)
                .query(String.class)
                .list();
    }

    List<UUID> findGodownIds(UUID businessId, UUID membershipId) {
        return jdbc.sql("""
                        SELECT godown_id FROM membership_godown
                        WHERE business_id = :businessId AND membership_id = :membershipId
                        ORDER BY godown_id
                        """)
                .param("businessId", businessId)
                .param("membershipId", membershipId)
                .query(UUID.class)
                .list();
    }

    private static MembershipRow map(ResultSet rs, int rowNum) throws SQLException {
        return new MembershipRow(
                rs.getObject("membership_id", UUID.class),
                rs.getString("membership_status"),
                rs.getBoolean("owner"),
                rs.getObject("business_id", UUID.class),
                rs.getString("business_name"),
                rs.getString("timezone"));
    }
}
