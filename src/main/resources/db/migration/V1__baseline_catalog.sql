CREATE TABLE baseline_release (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version_code VARCHAR(32) NOT NULL,
    description VARCHAR(255) NOT NULL,
    source_master_sha256 CHAR(64) NOT NULL,
    locked BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_baseline_release_version (version_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE baseline_document (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    baseline_release_id BIGINT NOT NULL,
    document_role VARCHAR(16) NOT NULL,
    process_code VARCHAR(32) NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    sheet_count INT NOT NULL DEFAULT 0,
    drawing_part_count INT NOT NULL DEFAULT 0,
    shape_count INT NOT NULL DEFAULT 0,
    connector_count INT NOT NULL DEFAULT 0,
    picture_count INT NOT NULL DEFAULT 0,
    content_bytes LONGBLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_baseline_document_release FOREIGN KEY (baseline_release_id) REFERENCES baseline_release(id),
    CONSTRAINT ck_baseline_document_role CHECK (document_role IN ('MASTER', 'PROCESS')),
    UNIQUE KEY uq_baseline_document_file (baseline_release_id, original_filename),
    KEY idx_baseline_document_process (baseline_release_id, process_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE import_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    baseline_release_id BIGINT NULL,
    run_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    source_filename VARCHAR(255) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    record_count INT NOT NULL DEFAULT 0,
    warning_count INT NOT NULL DEFAULT 0,
    summary_json LONGTEXT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    CONSTRAINT fk_import_run_release FOREIGN KEY (baseline_release_id) REFERENCES baseline_release(id),
    KEY idx_import_run_release (baseline_release_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workbook_sheet (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    baseline_document_id BIGINT NOT NULL,
    sheet_index INT NOT NULL,
    sheet_name VARCHAR(128) NOT NULL,
    state_name VARCHAR(16) NOT NULL DEFAULT 'visible',
    physical_row_count INT NOT NULL DEFAULT 0,
    last_column_index INT NOT NULL DEFAULT 0,
    drawing_part_name VARCHAR(255) NULL,
    shape_count INT NOT NULL DEFAULT 0,
    connector_count INT NOT NULL DEFAULT 0,
    picture_count INT NOT NULL DEFAULT 0,
    explicit_connector_bindings INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_workbook_sheet_document FOREIGN KEY (baseline_document_id) REFERENCES baseline_document(id),
    UNIQUE KEY uq_workbook_sheet_name (baseline_document_id, sheet_name),
    KEY idx_workbook_sheet_name (sheet_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE drawing_object (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    workbook_sheet_id BIGINT NOT NULL,
    package_part_name VARCHAR(255) NOT NULL,
    object_index INT NOT NULL,
    object_type VARCHAR(24) NOT NULL,
    shape_id VARCHAR(64) NULL,
    object_name VARCHAR(255) NULL,
    text_content LONGTEXT NULL,
    geometry_preset VARCHAR(64) NULL,
    start_col INT NULL,
    start_row INT NULL,
    start_col_offset BIGINT NULL,
    start_row_offset BIGINT NULL,
    end_col INT NULL,
    end_row INT NULL,
    end_col_offset BIGINT NULL,
    end_row_offset BIGINT NULL,
    start_connected_shape_id VARCHAR(64) NULL,
    end_connected_shape_id VARCHAR(64) NULL,
    head_arrow_type VARCHAR(32) NULL,
    tail_arrow_type VARCHAR(32) NULL,
    CONSTRAINT fk_drawing_object_sheet FOREIGN KEY (workbook_sheet_id) REFERENCES workbook_sheet(id),
    KEY idx_drawing_object_sheet_kind (workbook_sheet_id, object_type),
    KEY idx_drawing_object_shape (shape_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE equipment_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    baseline_release_id BIGINT NOT NULL,
    source_document_id BIGINT NOT NULL,
    source_sheet_name VARCHAR(128) NOT NULL,
    source_row_number INT NOT NULL,
    master_order INT NOT NULL,
    permit_management_no VARCHAR(96) NOT NULL,
    business_item_no VARCHAR(128) NULL,
    middle_process_code VARCHAR(32) NULL,
    major_process_code VARCHAR(32) NULL,
    process_category VARCHAR(255) NULL,
    facility_name VARCHAR(512) NULL,
    permit_name VARCHAR(512) NULL,
    permit_category VARCHAR(255) NULL,
    capacity_text VARCHAR(128) NULL,
    capacity_unit VARCHAR(64) NULL,
    quantity_text VARCHAR(128) NULL,
    operating_hours_text VARCHAR(128) NULL,
    annual_operating_days_text VARCHAR(128) NULL,
    purpose_text LONGTEXT NULL,
    pid_number VARCHAR(255) NULL,
    closed_status VARCHAR(64) NULL,
    paper_exists_status VARCHAR(64) NULL,
    raw_values_json LONGTEXT NOT NULL,
    CONSTRAINT fk_equipment_release FOREIGN KEY (baseline_release_id) REFERENCES baseline_release(id),
    CONSTRAINT fk_equipment_source_document FOREIGN KEY (source_document_id) REFERENCES baseline_document(id),
    UNIQUE KEY uq_equipment_release_permit (baseline_release_id, permit_management_no),
    KEY idx_equipment_process_code (baseline_release_id, major_process_code, middle_process_code),
    KEY idx_equipment_item_no (baseline_release_id, business_item_no),
    KEY idx_equipment_name (facility_name(128))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE equipment_flow (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    equipment_id BIGINT NOT NULL,
    flow_type VARCHAR(24) NOT NULL,
    material_type VARCHAR(255) NULL,
    quantity DECIMAL(20,6) NULL,
    quantity_source_text VARCHAR(128) NULL,
    quantity_unit VARCHAR(64) NULL,
    destination_text VARCHAR(512) NULL,
    completeness_status VARCHAR(24) NOT NULL,
    source_row_number INT NOT NULL,
    CONSTRAINT fk_equipment_flow_equipment FOREIGN KEY (equipment_id) REFERENCES equipment_record(id),
    CONSTRAINT ck_equipment_flow_type CHECK (flow_type IN ('WASTEWATER', 'WASTE')),
    CONSTRAINT ck_equipment_flow_status CHECK (completeness_status IN ('COMPLETE', 'REVIEW_REQUIRED', 'INVALID')),
    KEY idx_equipment_flow_type (flow_type, completeness_status),
    KEY idx_equipment_flow_destination (destination_text(128))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE equipment_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    baseline_release_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    process_document_id BIGINT NULL,
    workbook_sheet_id BIGINT NULL,
    mapping_status VARCHAR(32) NOT NULL,
    match_method VARCHAR(32) NULL,
    evidence_text VARCHAR(512) NULL,
    expected_process_code VARCHAR(32) NULL,
    CONSTRAINT fk_equipment_mapping_release FOREIGN KEY (baseline_release_id) REFERENCES baseline_release(id),
    CONSTRAINT fk_equipment_mapping_equipment FOREIGN KEY (equipment_id) REFERENCES equipment_record(id),
    CONSTRAINT fk_equipment_mapping_document FOREIGN KEY (process_document_id) REFERENCES baseline_document(id),
    CONSTRAINT fk_equipment_mapping_sheet FOREIGN KEY (workbook_sheet_id) REFERENCES workbook_sheet(id),
    KEY idx_equipment_mapping_status (baseline_release_id, mapping_status),
    KEY idx_equipment_mapping_doc_sheet (process_document_id, workbook_sheet_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE process_relation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    baseline_release_id BIGINT NOT NULL,
    source_equipment_id BIGINT NULL,
    target_equipment_id BIGINT NULL,
    process_document_id BIGINT NULL,
    workbook_sheet_id BIGINT NULL,
    source_drawing_object_id BIGINT NULL,
    target_drawing_object_id BIGINT NULL,
    relation_type VARCHAR(32) NOT NULL,
    flow_type VARCHAR(24) NULL,
    quantity DECIMAL(20,6) NULL,
    quantity_unit VARCHAR(64) NULL,
    destination_text VARCHAR(512) NULL,
    relation_status VARCHAR(24) NOT NULL DEFAULT 'REVIEW_REQUIRED',
    evidence_text LONGTEXT NULL,
    CONSTRAINT fk_process_relation_release FOREIGN KEY (baseline_release_id) REFERENCES baseline_release(id),
    CONSTRAINT fk_process_relation_source_equipment FOREIGN KEY (source_equipment_id) REFERENCES equipment_record(id),
    CONSTRAINT fk_process_relation_target_equipment FOREIGN KEY (target_equipment_id) REFERENCES equipment_record(id),
    CONSTRAINT fk_process_relation_document FOREIGN KEY (process_document_id) REFERENCES baseline_document(id),
    CONSTRAINT fk_process_relation_sheet FOREIGN KEY (workbook_sheet_id) REFERENCES workbook_sheet(id),
    CONSTRAINT fk_process_relation_source_object FOREIGN KEY (source_drawing_object_id) REFERENCES drawing_object(id),
    CONSTRAINT fk_process_relation_target_object FOREIGN KEY (target_drawing_object_id) REFERENCES drawing_object(id),
    CONSTRAINT ck_process_relation_status CHECK (relation_status IN ('CONFIRMED', 'INFERRED', 'REVIEW_REQUIRED')),
    KEY idx_process_relation_source (baseline_release_id, source_equipment_id),
    KEY idx_process_relation_target (baseline_release_id, target_equipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE validation_issue (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    baseline_release_id BIGINT NOT NULL,
    equipment_id BIGINT NULL,
    source_document_id BIGINT NULL,
    source_row_number INT NULL,
    severity VARCHAR(16) NOT NULL,
    issue_code VARCHAR(48) NOT NULL,
    field_name VARCHAR(128) NULL,
    detail_text LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_validation_issue_release FOREIGN KEY (baseline_release_id) REFERENCES baseline_release(id),
    CONSTRAINT fk_validation_issue_equipment FOREIGN KEY (equipment_id) REFERENCES equipment_record(id),
    CONSTRAINT fk_validation_issue_document FOREIGN KEY (source_document_id) REFERENCES baseline_document(id),
    KEY idx_validation_issue_release_code (baseline_release_id, issue_code, severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE change_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    baseline_release_id BIGINT NOT NULL,
    source_filename VARCHAR(255) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    source_content LONGBLOB NOT NULL,
    total_records INT NOT NULL DEFAULT 0,
    added_count INT NOT NULL DEFAULT 0,
    removed_count INT NOT NULL DEFAULT 0,
    modified_count INT NOT NULL DEFAULT 0,
    moved_count INT NOT NULL DEFAULT 0,
    validation_issue_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_change_run_release FOREIGN KEY (baseline_release_id) REFERENCES baseline_release(id),
    KEY idx_change_run_release_time (baseline_release_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE change_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    change_run_id BIGINT NOT NULL,
    change_type VARCHAR(24) NOT NULL,
    permit_management_no VARCHAR(96) NOT NULL,
    old_source_row INT NULL,
    new_source_row INT NULL,
    changed_fields_json LONGTEXT NULL,
    before_values_json LONGTEXT NULL,
    after_values_json LONGTEXT NULL,
    CONSTRAINT fk_change_item_run FOREIGN KEY (change_run_id) REFERENCES change_run(id),
    KEY idx_change_item_type (change_run_id, change_type),
    KEY idx_change_item_permit (permit_management_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE change_validation_issue (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    change_run_id BIGINT NOT NULL,
    permit_management_no VARCHAR(96) NULL,
    source_row_number INT NULL,
    severity VARCHAR(16) NOT NULL,
    issue_code VARCHAR(48) NOT NULL,
    field_name VARCHAR(128) NULL,
    detail_text LONGTEXT NOT NULL,
    CONSTRAINT fk_change_issue_run FOREIGN KEY (change_run_id) REFERENCES change_run(id),
    KEY idx_change_issue_code (change_run_id, issue_code, severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER //
CREATE TRIGGER trg_baseline_document_immutable_update
BEFORE UPDATE ON baseline_document
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Baseline source documents are immutable';
END//

CREATE TRIGGER trg_baseline_document_immutable_delete
BEFORE DELETE ON baseline_document
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Baseline source documents are immutable';
END//

CREATE TRIGGER trg_locked_release_immutable_update
BEFORE UPDATE ON baseline_release
FOR EACH ROW
BEGIN
    IF OLD.locked = TRUE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Locked baseline releases are immutable';
    END IF;
END//

CREATE TRIGGER trg_locked_release_immutable_delete
BEFORE DELETE ON baseline_release
FOR EACH ROW
BEGIN
    IF OLD.locked = TRUE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Locked baseline releases cannot be deleted';
    END IF;
END//

CREATE TRIGGER trg_locked_equipment_immutable_update
BEFORE UPDATE ON equipment_record
FOR EACH ROW
BEGIN
    IF (SELECT locked FROM baseline_release WHERE id = OLD.baseline_release_id) = TRUE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Equipment records in a locked baseline are immutable';
    END IF;
END//

CREATE TRIGGER trg_locked_equipment_immutable_delete
BEFORE DELETE ON equipment_record
FOR EACH ROW
BEGIN
    IF (SELECT locked FROM baseline_release WHERE id = OLD.baseline_release_id) = TRUE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Equipment records in a locked baseline cannot be deleted';
    END IF;
END//
DELIMITER ;
