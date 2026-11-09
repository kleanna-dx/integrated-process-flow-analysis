ALTER TABLE process_relation
    ADD COLUMN source_cell_range VARCHAR(32) NULL,
    ADD COLUMN target_cell_range VARCHAR(32) NULL;
