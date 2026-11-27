-- M05.S01-S05: inventory ledger.
--
-- stock_movement is the append-only ledger: every change of stock in a godown is one signed movement
-- in the product's base unit. stock_balance is the current-balance projection, updated in the same
-- transaction as the movements and never below zero. Reconciliation compares the two and records
-- variances in stock_discrepancy; it never rewrites either.
--
-- Postings lock the balance rows they touch in (godown_id, product_id) order, so concurrent postings
-- serialize per stock key without deadlocking.

-- One API request that posted stock: a godown and its lines (the movements).
CREATE TABLE stock_document (
    id                       UUID         PRIMARY KEY,
    business_id              UUID         NOT NULL REFERENCES business (id),
    type                     VARCHAR(16)  NOT NULL,
    godown_id                UUID         NOT NULL,
    -- Inward only: e.g. the supplier's bill number.
    reference                VARCHAR(100),
    -- Adjustments only.
    reason                   VARCHAR(32),
    note                     VARCHAR(500),
    created_by_membership_id UUID         NOT NULL,
    posted_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_stock_document_type CHECK (type IN ('OPENING', 'INWARD', 'ADJUSTMENT')),
    CONSTRAINT ck_stock_document_reason CHECK ((type = 'ADJUSTMENT') = (reason IS NOT NULL)),
    CONSTRAINT ck_stock_document_reference CHECK (type <> 'ADJUSTMENT' OR reference IS NULL),
    CONSTRAINT uq_stock_document_tenant UNIQUE (business_id, id),
    CONSTRAINT fk_stock_document_godown
        FOREIGN KEY (business_id, godown_id) REFERENCES godown (business_id, id),
    CONSTRAINT fk_stock_document_created_by
        FOREIGN KEY (business_id, created_by_membership_id) REFERENCES business_membership (business_id, id)
);

CREATE TABLE stock_movement (
    id                      UUID           PRIMARY KEY,
    -- Insertion order; breaks ties between movements posted at the same instant.
    seq                     BIGINT         GENERATED ALWAYS AS IDENTITY,
    business_id             UUID           NOT NULL REFERENCES business (id),
    godown_id               UUID           NOT NULL,
    product_id              UUID           NOT NULL,
    type                    VARCHAR(16)    NOT NULL,
    -- Signed change in the product's base unit.
    quantity                NUMERIC(19, 6) NOT NULL,
    -- What the member entered: quantity (same sign) in unit, and the base units one unit held then.
    entered_quantity        NUMERIC(19, 6) NOT NULL,
    entered_unit            VARCHAR(16)    NOT NULL,
    unit_factor             NUMERIC(19, 6) NOT NULL,
    balance_after           NUMERIC(19, 6) NOT NULL,
    -- What posted it: (STOCK_DOCUMENT, document, line) or (IMPORT, import, row). Unique, so a source
    -- line posts at most one movement.
    source_type             VARCHAR(32)    NOT NULL,
    source_id               UUID           NOT NULL,
    source_line             INTEGER        NOT NULL,
    actor_type              VARCHAR(16)    NOT NULL,
    actor_membership_id     UUID,
    initiator_membership_id UUID           NOT NULL,
    posted_at               TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_stock_movement_type CHECK (type IN ('OPENING', 'INWARD', 'ADJUSTMENT')),
    CONSTRAINT ck_stock_movement_quantity CHECK (quantity <> 0 AND (type = 'ADJUSTMENT' OR quantity > 0)),
    CONSTRAINT ck_stock_movement_entered CHECK (sign(entered_quantity) = sign(quantity)),
    CONSTRAINT ck_stock_movement_factor CHECK (unit_factor > 0),
    CONSTRAINT ck_stock_movement_balance CHECK (balance_after >= 0),
    CONSTRAINT ck_stock_movement_source_type CHECK (source_type IN ('STOCK_DOCUMENT', 'IMPORT')),
    CONSTRAINT ck_stock_movement_actor_type CHECK (actor_type IN ('USER', 'SYSTEM')),
    CONSTRAINT ck_stock_movement_actor CHECK ((actor_type = 'USER') = (actor_membership_id IS NOT NULL)),
    CONSTRAINT uq_stock_movement_seq UNIQUE (seq),
    CONSTRAINT uq_stock_movement_source UNIQUE (source_type, source_id, source_line),
    CONSTRAINT fk_stock_movement_godown
        FOREIGN KEY (business_id, godown_id) REFERENCES godown (business_id, id),
    CONSTRAINT fk_stock_movement_product
        FOREIGN KEY (business_id, product_id) REFERENCES product (business_id, id),
    CONSTRAINT fk_stock_movement_actor
        FOREIGN KEY (business_id, actor_membership_id) REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_stock_movement_initiator
        FOREIGN KEY (business_id, initiator_membership_id) REFERENCES business_membership (business_id, id)
);

-- History pages, newest first.
CREATE INDEX ix_stock_movement_history ON stock_movement (business_id, posted_at DESC, seq DESC);
CREATE INDEX ix_stock_movement_key ON stock_movement (business_id, godown_id, product_id, seq);

CREATE FUNCTION stock_movement_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'stock_movement is append-only'
        USING ERRCODE = 'insufficient_privilege', CONSTRAINT = 'stock_movement_append_only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_stock_movement_immutable
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION stock_movement_immutable();

-- Current stock of a product in a godown. A row exists once the pair has had a movement.
CREATE TABLE stock_balance (
    business_id       UUID           NOT NULL REFERENCES business (id),
    godown_id         UUID           NOT NULL,
    product_id        UUID           NOT NULL,
    quantity          NUMERIC(19, 6) NOT NULL,
    last_movement_seq BIGINT,
    updated_at        TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (business_id, godown_id, product_id),
    CONSTRAINT ck_stock_balance_quantity CHECK (quantity >= 0),
    CONSTRAINT fk_stock_balance_godown
        FOREIGN KEY (business_id, godown_id) REFERENCES godown (business_id, id),
    CONSTRAINT fk_stock_balance_product
        FOREIGN KEY (business_id, product_id) REFERENCES product (business_id, id)
);

-- A balance that disagreed with the sum of its movements. OPEN while the variance is still seen;
-- RESOLVED once a later run finds them in agreement again.
CREATE TABLE stock_discrepancy (
    id                UUID           PRIMARY KEY,
    business_id       UUID           NOT NULL REFERENCES business (id),
    godown_id         UUID           NOT NULL,
    product_id        UUID           NOT NULL,
    -- null when the pair has movements but no balance row (or the reverse for movement_quantity).
    balance_quantity  NUMERIC(25, 6),
    movement_quantity NUMERIC(25, 6),
    status            VARCHAR(16)    NOT NULL,
    first_detected_at TIMESTAMPTZ    NOT NULL,
    last_detected_at  TIMESTAMPTZ    NOT NULL,
    resolved_at       TIMESTAMPTZ,
    CONSTRAINT ck_stock_discrepancy_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_stock_discrepancy_resolved CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)),
    CONSTRAINT fk_stock_discrepancy_godown
        FOREIGN KEY (business_id, godown_id) REFERENCES godown (business_id, id),
    CONSTRAINT fk_stock_discrepancy_product
        FOREIGN KEY (business_id, product_id) REFERENCES product (business_id, id)
);

CREATE UNIQUE INDEX uq_stock_discrepancy_open ON stock_discrepancy (business_id, godown_id, product_id)
    WHERE status = 'OPEN';
