-- M03.S05-S07: tenant customers with versioned contacts, terms and credit policy, and approved aliases.
--
-- Customer names are deliberately not unique: two similar or identical names stay two customers
-- and are never merged automatically. Each row records which member created and last changed it.

CREATE TABLE customer (
    id                       UUID         PRIMARY KEY,
    business_id              UUID         NOT NULL REFERENCES business (id),
    name                     VARCHAR(200) NOT NULL,
    contact_person           VARCHAR(120),
    phone                    VARCHAR(20),
    address                  VARCHAR(500),
    -- Receivables policy, readable only with CUSTOMER_RECEIVABLES_READ.
    payment_terms_days       INTEGER,
    credit_limit             BIGINT,
    created_by_membership_id UUID         NOT NULL,
    updated_by_membership_id UUID         NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_customer_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_customer_payment_terms CHECK (payment_terms_days BETWEEN 0 AND 365),
    -- credit_limit is in integer minor units (paise); null means no limit has been set.
    CONSTRAINT ck_customer_credit_limit CHECK (credit_limit >= 0),
    -- Target for tenant-safe composite foreign keys from tenant-owned tables.
    CONSTRAINT uq_customer_tenant UNIQUE (business_id, id),
    CONSTRAINT fk_customer_created_by
        FOREIGN KEY (business_id, created_by_membership_id) REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_customer_updated_by
        FOREIGN KEY (business_id, updated_by_membership_id) REFERENCES business_membership (business_id, id)
);

CREATE INDEX ix_customer_name ON customer (business_id, lower(name));

-- Approved names a customer is also known by. The same alias may belong to several customers of a
-- business; lookups return every plausible match.
CREATE TABLE customer_alias (
    id                        UUID         PRIMARY KEY,
    business_id               UUID         NOT NULL,
    customer_id               UUID         NOT NULL,
    alias                     VARCHAR(120) NOT NULL,
    normalized_alias          VARCHAR(120) NOT NULL,
    approved_by_membership_id UUID         NOT NULL,
    approved_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_customer_alias CHECK (length(btrim(alias)) > 0),
    CONSTRAINT uq_customer_alias UNIQUE (customer_id, normalized_alias),
    CONSTRAINT fk_customer_alias_customer
        FOREIGN KEY (business_id, customer_id) REFERENCES customer (business_id, id),
    CONSTRAINT fk_customer_alias_approver
        FOREIGN KEY (business_id, approved_by_membership_id) REFERENCES business_membership (business_id, id)
);

CREATE INDEX ix_customer_alias_lookup ON customer_alias (business_id, normalized_alias);
