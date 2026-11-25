-- Product brand, pack size and price.
-- price is the selling price of one base unit in integer minor units (paise); null when not set.

ALTER TABLE product
    ADD COLUMN brand     VARCHAR(120),
    ADD COLUMN pack_size VARCHAR(60),
    ADD COLUMN price     BIGINT,
    ADD CONSTRAINT ck_product_price CHECK (price >= 0);
