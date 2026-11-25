-- Customer username: a required handle, unique within a business ignoring case. Another business may
-- use the same username. Existing customers get a generated one ("c-" + their id without dashes).

ALTER TABLE customer ADD COLUMN username VARCHAR(40);

UPDATE customer SET username = 'c-' || replace(id::text, '-', '');

ALTER TABLE customer
    ALTER COLUMN username SET NOT NULL,
    ADD CONSTRAINT ck_customer_username CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,39}$');

CREATE UNIQUE INDEX uq_customer_username ON customer (business_id, lower(username));
