-- V9 globally unique transfer_id prevented the same test route from being replayed
-- in an independent simulation. Existing rows remain intact; only uniqueness scope changes.
ALTER TABLE material_balance_transfer
    DROP INDEX uq_material_balance_transfer_id,
    ADD CONSTRAINT uq_material_balance_transfer_simulation_transfer
        UNIQUE (simulation_db_id, transfer_id);
