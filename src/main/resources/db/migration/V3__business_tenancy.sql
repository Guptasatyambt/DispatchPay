-- M01.S04: business tenants, memberships and ownership.
--
-- Ownership is a single NOT NULL pointer from the business to one of its memberships, so a business
-- has exactly one owner by construction: never zero, never two. Transferring ownership (M01.S13/S14)
-- updates that one column. Business and owner membership reference each other, so the pointer's
-- foreign key is deferred and both rows are inserted in one transaction.

CREATE TABLE business (
    id                  UUID         PRIMARY KEY,
    name                VARCHAR(120) NOT NULL,
    timezone            VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    owner_membership_id UUID         NOT NULL,
    created_by_user_id  UUID         NOT NULL REFERENCES user_account (id),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_business_status CHECK (status IN ('ACTIVE')),
    CONSTRAINT ck_business_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT uq_business_owner UNIQUE (owner_membership_id)
);

-- A user's access to one business. Owner or worker is not stored here: it follows from
-- business.owner_membership_id. Permissions and godown scopes arrive in later steps.
CREATE TABLE business_membership (
    id          UUID        PRIMARY KEY,
    business_id UUID        NOT NULL REFERENCES business (id),
    user_id     UUID        NOT NULL REFERENCES user_account (id),
    status      VARCHAR(16) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_business_membership_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT uq_business_membership_user UNIQUE (business_id, user_id),
    -- Target for tenant-safe composite foreign keys from tenant-owned tables.
    CONSTRAINT uq_business_membership_tenant UNIQUE (business_id, id)
);

CREATE INDEX ix_business_membership_user ON business_membership (user_id);

-- The owner must be a membership of the same business.
ALTER TABLE business
    ADD CONSTRAINT fk_business_owner_membership
        FOREIGN KEY (id, owner_membership_id) REFERENCES business_membership (business_id, id)
        DEFERRABLE INITIALLY DEFERRED;

-- An active business's owner membership must itself be active. Checked at commit so a single
-- transaction may change both sides (e.g. an ownership transfer).
CREATE FUNCTION check_business_owner_active(target_business_id UUID) RETURNS void AS $$
BEGIN
    IF EXISTS (SELECT 1
               FROM business b
               JOIN business_membership m ON m.id = b.owner_membership_id
               WHERE b.id = target_business_id AND b.status = 'ACTIVE' AND m.status <> 'ACTIVE') THEN
        RAISE EXCEPTION 'business % has no active owner', target_business_id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_business_owner_active';
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION business_owner_active_on_business() RETURNS trigger AS $$
BEGIN
    PERFORM check_business_owner_active(NEW.id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION business_owner_active_on_membership() RETURNS trigger AS $$
BEGIN
    PERFORM check_business_owner_active(NEW.business_id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER tr_business_owner_active
    AFTER INSERT OR UPDATE ON business
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION business_owner_active_on_business();

CREATE CONSTRAINT TRIGGER tr_business_membership_owner_active
    AFTER UPDATE ON business_membership
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION business_owner_active_on_membership();
