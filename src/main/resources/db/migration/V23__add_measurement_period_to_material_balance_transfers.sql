-- Periods are added for new TEST_ONLY transfers only. Existing rows are intentionally left NULL;
-- no historical period can be inferred from the old payload or relation schema.
ALTER TABLE material_balance_transfer
    ADD COLUMN measurement_period VARCHAR(128) NULL AFTER quantity_unit;
