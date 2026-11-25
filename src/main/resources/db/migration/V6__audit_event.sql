-- M02.S01: append-only audit of committed mutations.
--
-- Written in the mutation's own transaction, so a rolled-back mutation leaves no audit row.
-- Rows are immutable: UPDATE and DELETE are refused by trigger. Every membership reference is a
-- tenant-safe composite key, so an event can only be attributed to members of its own business.

CREATE TABLE audit_event (
    id                      UUID         PRIMARY KEY,
    business_id             UUID         NOT NULL REFERENCES business (id),
    occurred_at             TIMESTAMPTZ  NOT NULL,
    actor_type              VARCHAR(16)  NOT NULL,
    actor_membership_id     UUID,
    initiator_membership_id UUID,
    approver_membership_id  UUID,
    source                  VARCHAR(16)  NOT NULL,
    action                  VARCHAR(100) NOT NULL,
    resource_type           VARCHAR(64)  NOT NULL,
    resource_id             VARCHAR(64)  NOT NULL,
    changes                 JSONB        NOT NULL,
    correlation_id          VARCHAR(64),
    CONSTRAINT ck_audit_event_actor_type CHECK (actor_type IN ('USER', 'SYSTEM')),
    -- A user event names its member; a system event never carries a user as its actor.
    CONSTRAINT ck_audit_event_actor
        CHECK ((actor_type = 'USER') = (actor_membership_id IS NOT NULL)),
    CONSTRAINT fk_audit_event_actor
        FOREIGN KEY (business_id, actor_membership_id) REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_audit_event_initiator
        FOREIGN KEY (business_id, initiator_membership_id) REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_audit_event_approver
        FOREIGN KEY (business_id, approver_membership_id) REFERENCES business_membership (business_id, id)
);

CREATE INDEX ix_audit_event_business_time ON audit_event (business_id, occurred_at DESC, id);
CREATE INDEX ix_audit_event_resource ON audit_event (business_id, resource_type, resource_id, occurred_at DESC);

CREATE FUNCTION audit_event_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only'
        USING ERRCODE = 'insufficient_privilege', CONSTRAINT = 'audit_event_append_only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_audit_event_immutable
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_immutable();
