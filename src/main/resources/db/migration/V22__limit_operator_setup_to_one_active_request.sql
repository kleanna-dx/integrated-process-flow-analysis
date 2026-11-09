ALTER TABLE operator_setup_request
    ADD COLUMN active_slot TINYINT NULL AFTER state;

UPDATE operator_setup_request
SET active_slot = 1
WHERE state IN ('PENDING', 'APPROVED');

ALTER TABLE operator_setup_request
    ADD CONSTRAINT ck_operator_setup_active_slot CHECK (active_slot IS NULL OR active_slot = 1),
    ADD UNIQUE KEY uq_operator_setup_active_slot (active_slot);
