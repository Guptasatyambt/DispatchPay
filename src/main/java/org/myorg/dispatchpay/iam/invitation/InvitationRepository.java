package org.myorg.dispatchpay.iam.invitation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InvitationRepository {

    /** The business row, locked so invitation changes for one business serialize. */
    record LockedBusiness(String name, UUID ownerMembershipId) {
    }

    /** An invitation and its business, locked for acceptance. */
    record LockedInvitation(
            UUID id,
            UUID businessId,
            String businessName,
            String mobile,
            String status,
            Instant expiresAt) {
    }

    private final JdbcClient jdbc;

    InvitationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code FOR NO KEY UPDATE} so foreign-key checks from other tables are not blocked. */
    Optional<LockedBusiness> lockActiveBusiness(UUID businessId) {
        return jdbc.sql("""
                        SELECT name, owner_membership_id FROM business
                        WHERE id = :id AND status = 'ACTIVE'
                        FOR NO KEY UPDATE
                        """)
                .param("id", businessId)
                .query((rs, rowNum) -> new LockedBusiness(rs.getString("name"),
                        rs.getObject("owner_membership_id", UUID.class)))
                .optional();
    }

    boolean isMember(UUID businessId, String mobile) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM business_membership m JOIN user_account u ON u.id = m.user_id
                            WHERE m.business_id = :businessId AND u.mobile = :mobile)
                        """)
                .param("businessId", businessId)
                .param("mobile", mobile)
                .query(Boolean.class)
                .single();
    }

    void supersedePending(UUID businessId, String mobile, Instant now) {
        jdbc.sql("""
                        UPDATE worker_invitation SET status = 'SUPERSEDED', closed_at = :now
                        WHERE business_id = :businessId AND mobile = :mobile AND status = 'PENDING'
                        """)
                .param("now", Timestamp.from(now))
                .param("businessId", businessId)
                .param("mobile", mobile)
                .update();
    }

    void insert(UUID id, UUID businessId, String mobile, String tokenHash, UUID invitedByMembershipId,
            Instant now, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO worker_invitation
                            (id, business_id, mobile, token_hash, status, invited_by_membership_id,
                             created_at, expires_at)
                        VALUES (:id, :businessId, :mobile, :tokenHash, 'PENDING', :invitedBy, :now, :expiresAt)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("mobile", mobile)
                .param("tokenHash", tokenHash)
                .param("invitedBy", invitedByMembershipId)
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    /** Concurrent acceptances of one token serialize here. */
    Optional<LockedInvitation> lockByTokenHash(String tokenHash) {
        return jdbc.sql("""
                        SELECT i.id, i.business_id, b.name AS business_name, i.mobile, i.status, i.expires_at
                        FROM worker_invitation i
                        JOIN business b ON b.id = i.business_id
                        WHERE i.token_hash = :tokenHash AND b.status = 'ACTIVE'
                        FOR UPDATE OF i
                        """)
                .param("tokenHash", tokenHash)
                .query((rs, rowNum) -> new LockedInvitation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("business_id", UUID.class),
                        rs.getString("business_name"),
                        rs.getString("mobile"),
                        rs.getString("status"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    boolean isUserMember(UUID businessId, UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM business_membership
                                       WHERE business_id = :businessId AND user_id = :userId)
                        """)
                .param("businessId", businessId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    void markAccepted(UUID invitationId, UUID membershipId, Instant now) {
        jdbc.sql("""
                        UPDATE worker_invitation
                        SET status = 'ACCEPTED', accepted_membership_id = :membershipId, closed_at = :now
                        WHERE id = :id
                        """)
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .param("id", invitationId)
                .update();
    }
}
