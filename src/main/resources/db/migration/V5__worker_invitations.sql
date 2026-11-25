-- M01.S06/S07: owner-issued worker invitations.
--
-- An invitation is bound to one mobile and carries a single-use token, stored only as its SHA-256.
-- At most one invitation per business and mobile is pending: inviting again supersedes the previous
-- one, so only the newest token works. Expiry is derived from expires_at; a pending row past its
-- expiry is simply unusable.

CREATE TABLE worker_invitation (
    id                       UUID        PRIMARY KEY,
    business_id              UUID        NOT NULL REFERENCES business (id),
    mobile                   VARCHAR(16) NOT NULL,
    token_hash               CHAR(64)    NOT NULL,
    status                   VARCHAR(16) NOT NULL,
    invited_by_membership_id UUID        NOT NULL,
    accepted_membership_id   UUID,
    created_at               TIMESTAMPTZ NOT NULL,
    expires_at               TIMESTAMPTZ NOT NULL,
    closed_at                TIMESTAMPTZ,
    CONSTRAINT ck_worker_invitation_status CHECK (status IN ('PENDING', 'ACCEPTED', 'SUPERSEDED')),
    CONSTRAINT ck_worker_invitation_accepted
        CHECK ((status = 'ACCEPTED') = (accepted_membership_id IS NOT NULL)),
    CONSTRAINT ck_worker_invitation_closed CHECK ((status = 'PENDING') = (closed_at IS NULL)),
    CONSTRAINT uq_worker_invitation_token UNIQUE (token_hash),
    -- Tenant-safe: inviter and resulting membership belong to the invitation's business.
    CONSTRAINT fk_worker_invitation_inviter
        FOREIGN KEY (business_id, invited_by_membership_id) REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_worker_invitation_membership
        FOREIGN KEY (business_id, accepted_membership_id) REFERENCES business_membership (business_id, id)
);

CREATE UNIQUE INDEX uq_worker_invitation_pending ON worker_invitation (business_id, mobile)
    WHERE status = 'PENDING';
