-- M06: sales orders, their committed revisions and cancellations, and the dispatch drafts linked to
-- them (M07 completes dispatches on the same tables).
--
-- Orders never move stock: quantities are recorded here, and only a confirmed dispatch (M07) posts
-- to the stock ledger. Quantities are NUMERIC(19,6) in the product's base unit, as in the ledger;
-- money is integer minor units (paise).

-- Per-business counter for order numbers (SO-000001...), assigned when an order is confirmed.
CREATE TABLE sales_order_number (
    business_id UUID   PRIMARY KEY REFERENCES business (id),
    last_number BIGINT NOT NULL,
    CONSTRAINT ck_sales_order_number CHECK (last_number > 0)
);

CREATE TABLE sales_order (
    id                         UUID         PRIMARY KEY,
    business_id                UUID         NOT NULL REFERENCES business (id),
    number                     VARCHAR(20),
    status                     VARCHAR(24)  NOT NULL,
    customer_id                UUID         NOT NULL,
    godown_id                  UUID         NOT NULL,
    requested_date             DATE         NOT NULL,
    promised_date              DATE,
    note                       VARCHAR(500),
    -- The latest committed revision; 0 while the order is a draft.
    revision                   INTEGER      NOT NULL DEFAULT 0,
    created_by_membership_id   UUID         NOT NULL,
    updated_by_membership_id   UUID         NOT NULL,
    confirmed_by_membership_id UUID,
    created_at                 TIMESTAMPTZ  NOT NULL,
    updated_at                 TIMESTAMPTZ  NOT NULL,
    confirmed_at               TIMESTAMPTZ,
    version                    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_sales_order_status
        CHECK (status IN ('DRAFT', 'CONFIRMED', 'PARTIALLY_DISPATCHED', 'FULFILLED', 'CANCELLED')),
    -- A number and a confirmation exactly when the order was ever confirmed.
    CONSTRAINT ck_sales_order_confirmed CHECK (
        (number IS NULL AND confirmed_at IS NULL AND confirmed_by_membership_id IS NULL AND revision = 0)
        OR (number IS NOT NULL AND confirmed_at IS NOT NULL AND confirmed_by_membership_id IS NOT NULL
            AND revision > 0)),
    CONSTRAINT ck_sales_order_draft_number CHECK (status NOT IN ('CONFIRMED', 'PARTIALLY_DISPATCHED', 'FULFILLED')
        OR number IS NOT NULL),
    CONSTRAINT uq_sales_order_number UNIQUE (business_id, number),
    CONSTRAINT uq_sales_order_tenant UNIQUE (business_id, id),
    CONSTRAINT fk_sales_order_customer FOREIGN KEY (business_id, customer_id) REFERENCES customer (business_id, id),
    CONSTRAINT fk_sales_order_godown FOREIGN KEY (business_id, godown_id) REFERENCES godown (business_id, id),
    CONSTRAINT fk_sales_order_created_by
        FOREIGN KEY (business_id, created_by_membership_id) REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_sales_order_updated_by
        FOREIGN KEY (business_id, updated_by_membership_id) REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_sales_order_confirmed_by
        FOREIGN KEY (business_id, confirmed_by_membership_id) REFERENCES business_membership (business_id, id)
);

CREATE INDEX ix_sales_order_recent ON sales_order (business_id, created_at DESC, id DESC);
CREATE INDEX ix_sales_order_customer ON sales_order (business_id, customer_id, status);

-- Ordered quantity as entered (quantity, unit, unit_factor) and in the base unit. Dispatched and
-- cancelled quantities are in the base unit and together never exceed the ordered quantity.
CREATE TABLE sales_order_line (
    id                 UUID          PRIMARY KEY,
    business_id        UUID          NOT NULL,
    order_id           UUID          NOT NULL,
    line_no            INTEGER       NOT NULL,
    product_id         UUID          NOT NULL,
    quantity           NUMERIC(19,6) NOT NULL,
    unit               VARCHAR(16)   NOT NULL,
    unit_factor        NUMERIC(19,6) NOT NULL,
    base_quantity      NUMERIC(19,6) NOT NULL,
    -- Price of one entered unit; null on a draft means the product's price applies at confirmation.
    unit_price         BIGINT,
    discount_percent   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    tax_rate           NUMERIC(5,2)  NOT NULL DEFAULT 0,
    dispatched_quantity NUMERIC(19,6) NOT NULL DEFAULT 0,
    cancelled_quantity NUMERIC(19,6) NOT NULL DEFAULT 0,
    CONSTRAINT ck_sales_order_line_quantity CHECK (quantity > 0 AND unit_factor > 0 AND base_quantity > 0),
    CONSTRAINT ck_sales_order_line_price CHECK (unit_price >= 0),
    CONSTRAINT ck_sales_order_line_percent
        CHECK (discount_percent BETWEEN 0 AND 100 AND tax_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_sales_order_line_fulfilment CHECK (dispatched_quantity >= 0 AND cancelled_quantity >= 0
        AND dispatched_quantity + cancelled_quantity <= base_quantity),
    CONSTRAINT uq_sales_order_line_no UNIQUE (order_id, line_no),
    CONSTRAINT uq_sales_order_line_product UNIQUE (order_id, product_id),
    CONSTRAINT uq_sales_order_line_tenant UNIQUE (business_id, id),
    CONSTRAINT fk_sales_order_line_order FOREIGN KEY (business_id, order_id) REFERENCES sales_order (business_id, id),
    CONSTRAINT fk_sales_order_line_product FOREIGN KEY (business_id, product_id) REFERENCES product (business_id, id)
);

CREATE INDEX ix_sales_order_line_order ON sales_order_line (order_id, line_no);

-- Extra charges (freight, loading...): untaxed order-level amounts.
CREATE TABLE sales_order_charge (
    id          UUID        PRIMARY KEY,
    business_id UUID        NOT NULL,
    order_id    UUID        NOT NULL,
    position    INTEGER     NOT NULL,
    label       VARCHAR(60) NOT NULL,
    amount      BIGINT      NOT NULL,
    CONSTRAINT ck_sales_order_charge CHECK (length(btrim(label)) > 0 AND amount > 0),
    CONSTRAINT uq_sales_order_charge_position UNIQUE (order_id, position),
    CONSTRAINT fk_sales_order_charge_order
        FOREIGN KEY (business_id, order_id) REFERENCES sales_order (business_id, id)
);

-- Each cancellation request, with the quantity it took off each line.
CREATE TABLE sales_order_cancellation (
    id                         UUID         PRIMARY KEY,
    business_id                UUID         NOT NULL,
    order_id                   UUID         NOT NULL,
    reason                     VARCHAR(24)  NOT NULL,
    note                       VARCHAR(500),
    cancelled_by_membership_id UUID         NOT NULL,
    cancelled_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_sales_order_cancellation_reason
        CHECK (reason IN ('CUSTOMER_REQUEST', 'OUT_OF_STOCK', 'DUPLICATE', 'PRICING', 'OTHER')),
    CONSTRAINT ck_sales_order_cancellation_note CHECK (reason <> 'OTHER' OR note IS NOT NULL),
    CONSTRAINT uq_sales_order_cancellation_tenant UNIQUE (business_id, id),
    CONSTRAINT fk_sales_order_cancellation_order
        FOREIGN KEY (business_id, order_id) REFERENCES sales_order (business_id, id),
    CONSTRAINT fk_sales_order_cancellation_by
        FOREIGN KEY (business_id, cancelled_by_membership_id) REFERENCES business_membership (business_id, id)
);

CREATE INDEX ix_sales_order_cancellation_order ON sales_order_cancellation (order_id, cancelled_at);

-- What a cancellation took off each line: as entered (in the line's unit, or in the base unit when the
-- remainder is not a whole number of line units) and in the base unit.
CREATE TABLE sales_order_cancellation_line (
    cancellation_id UUID          NOT NULL,
    business_id     UUID          NOT NULL,
    order_line_id   UUID          NOT NULL,
    quantity        NUMERIC(19,6) NOT NULL,
    unit            VARCHAR(16)   NOT NULL,
    base_quantity   NUMERIC(19,6) NOT NULL,
    PRIMARY KEY (cancellation_id, order_line_id),
    CONSTRAINT ck_sales_order_cancellation_line CHECK (quantity > 0 AND base_quantity > 0),
    CONSTRAINT fk_sales_order_cancellation_line_cancellation
        FOREIGN KEY (business_id, cancellation_id) REFERENCES sales_order_cancellation (business_id, id),
    CONSTRAINT fk_sales_order_cancellation_line_line
        FOREIGN KEY (business_id, order_line_id) REFERENCES sales_order_line (business_id, id)
);

-- Committed revisions: an immutable snapshot written at confirmation (revision 1) and after every
-- later change to a confirmed order.
CREATE TABLE sales_order_revision (
    order_id                 UUID        NOT NULL,
    revision                 INTEGER     NOT NULL,
    business_id              UUID        NOT NULL,
    change                   VARCHAR(16) NOT NULL,
    snapshot                 JSONB       NOT NULL,
    created_by_membership_id UUID        NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (order_id, revision),
    CONSTRAINT ck_sales_order_revision CHECK (revision > 0 AND change IN ('CONFIRM', 'EDIT', 'CANCEL')),
    CONSTRAINT fk_sales_order_revision_order
        FOREIGN KEY (business_id, order_id) REFERENCES sales_order (business_id, id),
    CONSTRAINT fk_sales_order_revision_by
        FOREIGN KEY (business_id, created_by_membership_id) REFERENCES business_membership (business_id, id)
);

CREATE FUNCTION sales_order_revision_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'order revisions are append-only'
        USING ERRCODE = 'integrity_constraint_violation', CONSTRAINT = 'sales_order_revision_immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sales_order_revision_immutable
    BEFORE UPDATE OR DELETE ON sales_order_revision
    FOR EACH ROW EXECUTE FUNCTION sales_order_revision_immutable();

-- Dispatches (M07). M06 creates DRAFT dispatches from orders; a draft has no stock effect. Lines
-- linked to an order line are rechecked against its remaining quantity when the dispatch is
-- confirmed, so competing drafts cannot over-fulfil an order.
CREATE TABLE dispatch (
    id                       UUID         PRIMARY KEY,
    business_id              UUID         NOT NULL REFERENCES business (id),
    status                   VARCHAR(16)  NOT NULL,
    customer_id              UUID         NOT NULL,
    godown_id                UUID         NOT NULL,
    order_id                 UUID,
    note                     VARCHAR(500),
    created_by_membership_id UUID         NOT NULL,
    updated_by_membership_id UUID         NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_dispatch_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT uq_dispatch_tenant UNIQUE (business_id, id),
    CONSTRAINT fk_dispatch_customer FOREIGN KEY (business_id, customer_id) REFERENCES customer (business_id, id),
    CONSTRAINT fk_dispatch_godown FOREIGN KEY (business_id, godown_id) REFERENCES godown (business_id, id),
    CONSTRAINT fk_dispatch_order FOREIGN KEY (business_id, order_id) REFERENCES sales_order (business_id, id),
    CONSTRAINT fk_dispatch_created_by
        FOREIGN KEY (business_id, created_by_membership_id) REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_dispatch_updated_by
        FOREIGN KEY (business_id, updated_by_membership_id) REFERENCES business_membership (business_id, id)
);

CREATE INDEX ix_dispatch_order ON dispatch (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX ix_dispatch_recent ON dispatch (business_id, created_at DESC, id DESC);

CREATE TABLE dispatch_line (
    id            UUID          PRIMARY KEY,
    business_id   UUID          NOT NULL,
    dispatch_id   UUID          NOT NULL,
    line_no       INTEGER       NOT NULL,
    product_id    UUID          NOT NULL,
    order_line_id UUID,
    quantity      NUMERIC(19,6) NOT NULL,
    unit          VARCHAR(16)   NOT NULL,
    unit_factor   NUMERIC(19,6) NOT NULL,
    base_quantity NUMERIC(19,6) NOT NULL,
    CONSTRAINT ck_dispatch_line_quantity CHECK (quantity > 0 AND unit_factor > 0 AND base_quantity > 0),
    CONSTRAINT uq_dispatch_line_no UNIQUE (dispatch_id, line_no),
    CONSTRAINT fk_dispatch_line_dispatch FOREIGN KEY (business_id, dispatch_id) REFERENCES dispatch (business_id, id),
    CONSTRAINT fk_dispatch_line_product FOREIGN KEY (business_id, product_id) REFERENCES product (business_id, id),
    CONSTRAINT fk_dispatch_line_order_line
        FOREIGN KEY (business_id, order_line_id) REFERENCES sales_order_line (business_id, id)
);

CREATE INDEX ix_dispatch_line_order_line ON dispatch_line (order_line_id) WHERE order_line_id IS NOT NULL;
