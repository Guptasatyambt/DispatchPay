package org.myorg.dispatchpay.iam.business;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.myorg.dispatchpay.iam.membership.MembershipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Business tenants. The creator becomes the owner through an active membership. */
@Service
public class BusinessService {

    private static final Logger log = LoggerFactory.getLogger(BusinessService.class);

    public record BusinessCreated(
            UUID id,
            String name,
            String timezone,
            String status,
            UUID membershipId,
            String role,
            Instant createdAt) {
    }

    private final BusinessRepository businesses;
    private final MembershipRepository memberships;
    private final Clock clock;

    public BusinessService(BusinessRepository businesses, MembershipRepository memberships, Clock clock) {
        this.businesses = businesses;
        this.memberships = memberships;
        this.clock = clock;
    }

    /**
     * Creates the business, the creator's membership and the ownership link together. Runs inside
     * the idempotent command's transaction, so a retried request cannot create a second business.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BusinessCreated create(UUID ownerUserId, String name, String timezone) {
        Instant now = clock.instant();
        UUID businessId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        businesses.insert(businessId, name, timezone, membershipId, ownerUserId, now);
        memberships.insert(membershipId, businessId, ownerUserId, MembershipRepository.Status.ACTIVE, now);
        log.info("User {} created business {}", ownerUserId, businessId);
        return new BusinessCreated(businessId, name, timezone, "ACTIVE", membershipId, "OWNER", now);
    }
}
