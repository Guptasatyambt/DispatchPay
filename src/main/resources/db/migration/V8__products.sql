-- M03.S02-S04: tenant product catalog, approved aliases and product-specific unit conversions.
--
-- Products are never deleted (they are deactivated) and their base unit never changes, so quantities
-- and snapshots recorded against a product keep their meaning. Units are validated by the
-- application (Unit enum) so the list can grow without a migration.

CREATE TABLE product (
    id          UUID         PRIMARY KEY,
    business_id UUID         NOT NULL REFERENCES business (id),
    sku         VARCHAR(64)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    base_unit   VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_product_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_product_sku CHECK (length(btrim(sku)) > 0),
    CONSTRAINT ck_product_name CHECK (length(btrim(name)) > 0),
    -- Target for tenant-safe composite foreign keys from tenant-owned tables.
    CONSTRAINT uq_product_tenant UNIQUE (business_id, id)
);

-- SKUs are unique within a business, ignoring case; other businesses may reuse them.
CREATE UNIQUE INDEX uq_product_sku ON product (business_id, lower(sku));
CREATE INDEX ix_product_name ON product (business_id, lower(name));

CREATE FUNCTION product_history_stable() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'products are deactivated, never deleted'
            USING ERRCODE = 'integrity_constraint_violation', CONSTRAINT = 'product_not_deletable';
    END IF;
    IF NEW.base_unit <> OLD.base_unit THEN
        RAISE EXCEPTION 'the base unit of product % cannot change', OLD.id
            USING ERRCODE = 'integrity_constraint_violation', CONSTRAINT = 'product_base_unit_immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_product_history_stable
    BEFORE UPDATE OR DELETE ON product
    FOR EACH ROW EXECUTE FUNCTION product_history_stable();

-- Approved names a product is also known by. The same alias may belong to several products of a
-- business (brand or pack ambiguity); lookups return every candidate.
CREATE TABLE product_alias (
    id                        UUID         PRIMARY KEY,
    business_id               UUID         NOT NULL,
    product_id                UUID         NOT NULL,
    alias                     VARCHAR(120) NOT NULL,
    normalized_alias          VARCHAR(120) NOT NULL,
    approved_by_membership_id UUID         NOT NULL,
    approved_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_product_alias CHECK (length(btrim(alias)) > 0),
    CONSTRAINT uq_product_alias UNIQUE (product_id, normalized_alias),
    CONSTRAINT fk_product_alias_product
        FOREIGN KEY (business_id, product_id) REFERENCES product (business_id, id),
    CONSTRAINT fk_product_alias_approver
        FOREIGN KEY (business_id, approved_by_membership_id) REFERENCES business_membership (business_id, id)
);

CREATE INDEX ix_product_alias_lookup ON product_alias (business_id, normalized_alias);

-- factor = how many base units one `unit` holds, e.g. BOX = 12 when the base unit is PCS.
CREATE TABLE product_unit_conversion (
    business_id UUID          NOT NULL,
    product_id  UUID          NOT NULL,
    unit        VARCHAR(16)   NOT NULL,
    factor      NUMERIC(19, 6) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (product_id, unit),
    CONSTRAINT ck_product_unit_conversion_factor CHECK (factor > 0),
    CONSTRAINT fk_product_unit_conversion_product
        FOREIGN KEY (business_id, product_id) REFERENCES product (business_id, id)
);
