-- M04.S01-S04: CSV imports of products, customers and opening stock.
--
-- The file is uploaded to private object storage, parsed by a background worker into staging rows
-- (import_row) and only committed to the business tables after every row validated. The staged
-- rows are the immutable version that is committed; the file is never read again after validation.
-- The import's status is the durable job state: workers claim it with a lease, so a crashed worker's
-- import is picked up again and finishing steps are guarded by the lease.

CREATE TABLE import_job (
    id                         UUID         PRIMARY KEY,
    business_id                UUID         NOT NULL REFERENCES business (id),
    type                       VARCHAR(32)  NOT NULL,
    status                     VARCHAR(32)  NOT NULL,
    file_name                  VARCHAR(255) NOT NULL,
    declared_size              BIGINT       NOT NULL,
    object_key                 VARCHAR(512) NOT NULL,
    upload_expires_at          TIMESTAMPTZ  NOT NULL,
    -- Measured by the worker from the stored object.
    file_size                  BIGINT,
    file_sha256                VARCHAR(64),
    -- The header's columns in file order, by their documented names.
    columns                    JSONB,
    total_rows                 INTEGER,
    error_rows                 INTEGER,
    failure_code               VARCHAR(64),
    failure_message            VARCHAR(1000),
    -- Error report in object storage, written on first download.
    error_object_key           VARCHAR(512),
    created_by_membership_id   UUID         NOT NULL,
    validated_by_membership_id UUID,
    committed_by_membership_id UUID,
    -- Worker lease: the worker holding lease_owner until lease_expires_at may finish the step.
    lease_owner                UUID,
    lease_expires_at           TIMESTAMPTZ,
    attempts                   INTEGER      NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ  NOT NULL,
    updated_at                 TIMESTAMPTZ  NOT NULL,
    validated_at               TIMESTAMPTZ,
    committed_at               TIMESTAMPTZ,
    CONSTRAINT ck_import_job_type CHECK (type IN ('PRODUCTS', 'CUSTOMERS', 'OPENING_STOCK')),
    CONSTRAINT ck_import_job_status CHECK (status IN ('AWAITING_UPLOAD', 'VALIDATING', 'VALIDATED', 'INVALID',
                                                      'COMMITTING', 'COMMITTED', 'FAILED')),
    CONSTRAINT ck_import_job_declared_size CHECK (declared_size > 0),
    CONSTRAINT uq_import_job_object UNIQUE (object_key),
    -- Target for tenant-safe composite foreign keys from tenant-owned tables.
    CONSTRAINT uq_import_job_tenant UNIQUE (business_id, id),
    CONSTRAINT fk_import_job_created_by
        FOREIGN KEY (business_id, created_by_membership_id) REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_import_job_validated_by
        FOREIGN KEY (business_id, validated_by_membership_id) REFERENCES business_membership (business_id, id),
    CONSTRAINT fk_import_job_committed_by
        FOREIGN KEY (business_id, committed_by_membership_id) REFERENCES business_membership (business_id, id)
);

-- Work queue: imports waiting for a worker.
CREATE INDEX ix_import_job_pending ON import_job (updated_at) WHERE status IN ('VALIDATING', 'COMMITTING');

-- One staged CSV record. row_number is the record's position in the file (the header is row 1), so
-- errors point at the row the user sees in a spreadsheet. A committed row records what it created,
-- and (import, row) is unique, so a source row can be committed at most once.
CREATE TABLE import_row (
    business_id UUID    NOT NULL,
    import_id   UUID    NOT NULL,
    row_number  INTEGER NOT NULL,
    -- Raw cell values by column, exactly as in the file.
    data        JSONB   NOT NULL,
    -- [{column, code, message}]; empty when the row is valid.
    errors      JSONB   NOT NULL DEFAULT '[]',
    resource_id UUID,
    PRIMARY KEY (import_id, row_number),
    CONSTRAINT ck_import_row_number CHECK (row_number > 1),
    CONSTRAINT fk_import_row_import
        FOREIGN KEY (business_id, import_id) REFERENCES import_job (business_id, id) ON DELETE CASCADE
);
