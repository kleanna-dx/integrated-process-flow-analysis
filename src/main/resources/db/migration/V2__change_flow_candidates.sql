CREATE TABLE change_flow_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    change_run_id BIGINT NOT NULL,
    permit_management_no VARCHAR(96) NOT NULL,
    flow_type VARCHAR(24) NOT NULL,
    material_type VARCHAR(255) NULL,
    quantity DECIMAL(20,6) NULL,
    quantity_source_text VARCHAR(128) NULL,
    quantity_unit VARCHAR(64) NULL,
    destination_text VARCHAR(512) NULL,
    matched_target_permit_no VARCHAR(96) NULL,
    matched_target_item_no VARCHAR(128) NULL,
    matched_process_code VARCHAR(32) NULL,
    resolution_status VARCHAR(32) NOT NULL,
    route_created BOOLEAN NOT NULL DEFAULT FALSE,
    source_row_number INT NOT NULL,
    source_values_json LONGTEXT NOT NULL,
    CONSTRAINT fk_change_flow_candidate_run FOREIGN KEY (change_run_id) REFERENCES change_run(id),
    CONSTRAINT ck_change_flow_candidate_type CHECK (flow_type IN ('WASTEWATER', 'WASTE')),
    CONSTRAINT ck_change_flow_candidate_status CHECK (resolution_status IN (
        'EXPLICIT_ZERO', 'READY_FOR_REVIEW', 'INCOMPLETE', 'INVALID', 'UNRESOLVED_DESTINATION')),
    KEY idx_change_flow_run (change_run_id, flow_type, resolution_status),
    KEY idx_change_flow_permit (permit_management_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
