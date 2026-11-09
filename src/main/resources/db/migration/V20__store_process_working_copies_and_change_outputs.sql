CREATE TABLE process_working_document (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    baseline_document_id BIGINT NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    working_sha256 CHAR(64) NOT NULL,
    conversion_version VARCHAR(32) NOT NULL,
    conversion_status VARCHAR(32) NOT NULL,
    source_filename VARCHAR(255) NOT NULL,
    working_filename VARCHAR(255) NOT NULL,
    conversion_report_json LONGTEXT NOT NULL,
    content_bytes LONGBLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_process_working_baseline_document
        FOREIGN KEY (baseline_document_id) REFERENCES baseline_document(id),
    CONSTRAINT ck_process_working_conversion_status
        CHECK (conversion_status IN ('CREATED', 'CREATED_WITH_REVIEW')),
    UNIQUE KEY uq_process_working_source_version (baseline_document_id, source_sha256, conversion_version),
    KEY idx_process_working_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE process_change_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    working_document_id BIGINT NOT NULL,
    process_code VARCHAR(32) NOT NULL,
    sheet_name VARCHAR(128) NOT NULL,
    change_type VARCHAR(16) NOT NULL,
    equipment_management_no VARCHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    plan_json LONGTEXT NOT NULL,
    request_status VARCHAR(24) NOT NULL DEFAULT 'PREVIEWED',
    calculation_status VARCHAR(40) NOT NULL DEFAULT 'CALCULATION_UNAVAILABLE',
    output_filename VARCHAR(255) NULL,
    output_sha256 CHAR(64) NULL,
    output_content LONGBLOB NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    generated_at TIMESTAMP NULL,
    CONSTRAINT fk_process_change_working_document
        FOREIGN KEY (working_document_id) REFERENCES process_working_document(id),
    CONSTRAINT ck_process_change_mode CHECK (change_type IN ('ADD', 'MODIFY', 'REMOVE')),
    CONSTRAINT ck_process_change_status CHECK (request_status IN ('PREVIEWED', 'GENERATED', 'GENERATION_BLOCKED')),
    KEY idx_process_change_request_scope (process_code, sheet_name, created_at),
    KEY idx_process_change_working_document (working_document_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
