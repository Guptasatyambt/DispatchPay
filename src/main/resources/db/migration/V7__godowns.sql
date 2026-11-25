-- M03.S01: tenant-owned godowns with versioned edits.
-- M01.S10: godown scopes of worker memberships.

CREATE TABLE godown (
    id          UUID         PRIMARY KEY,
    business_id UUID         NOT NULL REFERENCES business (id),
    name        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_godown_name CHECK (length(btrim(name)) > 0),
    -- Target for tenant-safe composite foreign keys from tenant-owned tables.
    CONSTRAINT uq_godown_tenant UNIQUE (business_id, id)
);

-- Godown names are unique within a business, ignoring case.
CREATE UNIQUE INDEX uq_godown_name ON godown (business_id, lower(name));

-- Godowns a worker may act in. The owner is never listed: ownership implies every godown.
-- Both keys are composite with the business, so a scope can only name the business's own godowns.
CREATE TABLE membership_godown (
    business_id   UUID        NOT NULL,
    membership_id UUID        NOT NULL,
    godown_id     UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (membership_id, godown_id),
    CONSTRAINT fk_membership_godown_membership
        FOREIGN KEY (business_id, membership_id) REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_membership_godown_godown
        FOREIGN KEY (business_id, godown_id) REFERENCES godown (business_id, id)
);
