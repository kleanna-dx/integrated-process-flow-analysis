CREATE TABLE diagram_edit_integrated_output (
    plan_id BIGINT NOT NULL,
    scenario_key VARCHAR(32) NOT NULL,
    change_run_id BIGINT NOT NULL,
    change_item_id BIGINT NOT NULL,
    source_diagram_sha256 CHAR(64) NOT NULL,
    output_sha256 CHAR(64) NOT NULL,
    output_filename VARCHAR(255) NOT NULL,
    output_content LONGBLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (plan_id, scenario_key),
    CONSTRAINT fk_integrated_output_plan FOREIGN KEY (plan_id) REFERENCES diagram_edit_plan(id),
    CONSTRAINT ck_integrated_output_scenario CHECK (scenario_key IN ('ACTUAL_REVIEW_ONLY', 'SYNTHETIC_TEST_ONLY')),
    KEY idx_integrated_output_run_item (change_run_id, change_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
