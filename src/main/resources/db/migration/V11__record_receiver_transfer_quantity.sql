-- Preserve the independently reported receiving-side quantity when supplied.
-- NULL means the receiver quantity was not independently measured/provided.
ALTER TABLE material_balance_transfer
    ADD COLUMN receiver_quantity DECIMAL(20,6) NULL AFTER quantity;
