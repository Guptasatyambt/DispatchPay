-- M01.S05: action permissions granted to worker memberships.
--
-- Workers are default-deny: a membership can do exactly what is listed here. The owner is never
-- listed; ownership (business.owner_membership_id) implies the whole catalog. Permission codes are
-- defined by the application (Permission enum) rather than a CHECK, so the catalog can grow without
-- a migration; codes the application no longer knows are ignored when access is evaluated.
-- Grants are written by M01.S09; godown scopes arrive with godowns (M03.S01) and M01.S10.

CREATE TABLE membership_permission (
    business_id   UUID        NOT NULL,
    membership_id UUID        NOT NULL,
    permission    VARCHAR(64) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (membership_id, permission),
    -- Tenant-safe: the grant belongs to the same business as its membership.
    CONSTRAINT fk_membership_permission_membership
        FOREIGN KEY (business_id, membership_id) REFERENCES business_membership (business_id, id)
);
