ALTER TABLE change_run
    ADD COLUMN analysis_mode VARCHAR(16) NOT NULL DEFAULT 'TEST_ONLY' AFTER source_sha256,
    ADD COLUMN diagram_mapping_status VARCHAR(48) NOT NULL DEFAULT 'REVIEW_REQUIRED' AFTER validation_issue_count,
    ADD COLUMN automatic_mapping_succeeded BOOLEAN NOT NULL DEFAULT FALSE AFTER diagram_mapping_status,
    ADD COLUMN affected_diagrams_json LONGTEXT NULL AFTER automatic_mapping_succeeded,
    ADD COLUMN review_required_json LONGTEXT NULL AFTER affected_diagrams_json;

ALTER TABLE change_item
    ADD COLUMN diagram_mapping_status VARCHAR(48) NOT NULL DEFAULT 'REVIEW_REQUIRED' AFTER after_values_json,
    ADD COLUMN automatic_mapping_succeeded BOOLEAN NOT NULL DEFAULT FALSE AFTER diagram_mapping_status,
    ADD COLUMN affected_diagrams_json LONGTEXT NULL AFTER automatic_mapping_succeeded,
    ADD COLUMN review_required_json LONGTEXT NULL AFTER affected_diagrams_json;
