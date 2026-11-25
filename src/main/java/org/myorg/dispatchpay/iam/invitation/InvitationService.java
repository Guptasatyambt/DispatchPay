package org.myorg.dispatchpay.iam.invitation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.auth.MobileNumber;
import org.myorg.dispatchpay.iam.invitation.InvitationRepository.LockedBusiness;
import org.myorg.dispatchpay.iam.invitation.InvitationRepository.LockedInvitation;
import org.myorg.dispatchpay.iam.membership.MembershipRepository;
import org.myorg.dispatchpay.iam.user.UserAccountRepository;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-issued, mobile-bound, single-use worker invitations. The raw token exists only in memory
 * and in the message to the invitee; the database keeps its SHA-256.
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    public record InvitationCreated(
            UUID id,
            UUID businessId,
            String businessName,
            String mobile,
            String status,
            Instant createdAt,
            Instant expiresAt) {
    }

    public record InvitationAccepted(
            UUID membershipId,
            UUID businessId,
            String businessName,
            BusinessAccess.Role role,
            String status) {
    }

    private final InvitationRepository invitations;
    private final MembershipRepository memberships;
    private final UserAccountRepository users;
    private final InvitationSender sender;
    private final InvitationProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public InvitationService(InvitationRepository invitations, MembershipRepository memberships,
            UserAccountRepository users, InvitationSender sender, InvitationProperties properties, Clock clock) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.users = users;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    /** 256-bit URL-safe token. Generated outside the command so a replayed request never sends it. */
    public String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Invites a mobile to the business. Any pending invitation for the same mobile is superseded,
     * so re-inviting is how an owner resends. Runs inside the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InvitationCreated create(BusinessAccess access, MobileNumber mobile, String token) {
        // Re-checked under the business lock so a concurrent ownership change cannot slip through.
        LockedBusiness business = invitations.lockActiveBusiness(access.businessId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Business not found"));
        if (!business.ownerMembershipId().equals(access.membershipId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the business owner can perform this action");
        }
        if (invitations.isMember(access.businessId(), mobile.e164())) {
            throw new ApiException(ErrorCode.ALREADY_MEMBER, "This mobile number is already a member of the business");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.ttl());
        UUID id = UUID.randomUUID();
        invitations.supersedePending(access.businessId(), mobile.e164(), now);
        invitations.insert(id, access.businessId(), mobile.e164(), sha256(token), access.membershipId(), now,
                expiresAt);
        log.info("Membership {} invited {} to business {}", access.membershipId(), mobile.masked(),
                access.businessId());
        return new InvitationCreated(id, access.businessId(), business.name(), mobile.e164(), "PENDING", now,
                expiresAt);
    }

    /** Called after the invitation commits. Failures are logged; the owner can re-invite to resend. */
    public void deliver(InvitationCreated invitation, String token) {
        MobileNumber mobile = MobileNumber.parse(invitation.mobile());
        try {
            sender.send(mobile, invitation.businessName(), token, invitation.expiresAt());
        } catch (RuntimeException e) {
            log.error("Invitation {} delivery to {} failed", invitation.id(), mobile.masked(), e);
        }
    }

    /**
     * Accepts an invitation for the signed-in user, whose mobile must be the invited one. Anyone
     * else is told the invitation does not exist, so a leaked token reveals nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InvitationAccepted accept(UUID userId, String token) {
        String mobile = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required"))
                .mobile();
        LockedInvitation invitation = invitations.lockByTokenHash(sha256(token))
                .filter(found -> found.mobile().equals(mobile))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Invitation not found"));

        Instant now = clock.instant();
        switch (invitation.status()) {
            case "ACCEPTED" -> throw new ApiException(ErrorCode.INVITATION_ALREADY_USED,
                    "This invitation has already been accepted");
            case "SUPERSEDED" -> throw new ApiException(ErrorCode.INVITATION_SUPERSEDED,
                    "This invitation was replaced by a newer one");
            default -> {
                if (!invitation.expiresAt().isAfter(now)) {
                    throw new ApiException(ErrorCode.INVITATION_EXPIRED, "This invitation has expired");
                }
            }
        }
        if (invitations.isUserMember(invitation.businessId(), userId)) {
            throw new ApiException(ErrorCode.ALREADY_MEMBER, "You are already a member of this business");
        }

        UUID membershipId = UUID.randomUUID();
        memberships.insert(membershipId, invitation.businessId(), userId, MembershipRepository.Status.ACTIVE, now);
        invitations.markAccepted(invitation.id(), membershipId, now);
        log.info("User {} accepted invitation {} to business {}", userId, invitation.id(), invitation.businessId());
        return new InvitationAccepted(membershipId, invitation.businessId(), invitation.businessName(),
                BusinessAccess.Role.WORKER, MembershipRepository.Status.ACTIVE.name());
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
