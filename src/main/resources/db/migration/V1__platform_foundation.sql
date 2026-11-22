-- M00.S02 platform foundation: idempotent commands, transactional outbox, idempotent consumers.

-- One row per (scope, operation, key). Written in the same transaction as the command it guards,
-- so a committed row always carries the original response and a rolled-back command leaves no row.
CREATE TABLE idempotency_record (
    id              UUID         PRIMARY KEY,
    scope           VARCHAR(200) NOT NULL,
    operation       VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    response_body   JSONB,
    created_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idempotency_record UNIQUE (scope, operation, idempotency_key)
);

CREATE INDEX ix_idempotency_record_expires_at ON idempotency_record (expires_at);

-- Events appended inside business transactions and delivered at-least-once by the outbox worker.
CREATE TABLE outbox_event (
    id              UUID          PRIMARY KEY,
    aggregate_type  VARCHAR(100)  NOT NULL,
    aggregate_id    VARCHAR(100)  NOT NULL,
    event_type      VARCHAR(150)  NOT NULL,
    payload         JSONB         NOT NULL,
    tenant_id       UUID,
    correlation_id  VARCHAR(64),
    metadata        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(16)   NOT NULL,
    attempts        INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL,
    last_error      VARCHAR(2000),
    created_at      TIMESTAMPTZ   NOT NULL,
    published_at    TIMESTAMPTZ,
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD'))
);

CREATE INDEX ix_outbox_event_pending ON outbox_event (next_attempt_at) WHERE status = 'PENDING';

-- Consumer-side de-duplication: a handler applies an event at most once.
CREATE TABLE processed_event (
    handler      VARCHAR(150) NOT NULL,
    event_id     UUID         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (handler, event_id)
);
