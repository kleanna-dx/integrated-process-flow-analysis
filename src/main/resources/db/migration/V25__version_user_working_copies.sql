ALTER TABLE process_working_document
    DROP INDEX uq_process_working_source_version,
    ADD COLUMN working_copy_kind VARCHAR(24) NOT NULL DEFAULT 'SYSTEM_GENERATED' AFTER conversion_version,
    ADD COLUMN registered_by VARCHAR(128) NOT NULL DEFAULT 'SYSTEM' AFTER working_copy_kind,
    ADD COLUMN version_number INT NOT NULL DEFAULT 1 AFTER registered_by,
    ADD COLUMN previous_working_document_id BIGINT NULL AFTER version_number,
    ADD COLUMN working_size_bytes BIGINT NOT NULL DEFAULT 0 AFTER previous_working_document_id,
    ADD COLUMN sheet_metadata_json LONGTEXT NULL AFTER conversion_report_json,
    ADD COLUMN verification_status VARCHAR(24) NOT NULL DEFAULT 'REVIEW_REQUIRED' AFTER sheet_metadata_json,
    ADD CONSTRAINT fk_process_working_previous_version
        FOREIGN KEY (previous_working_document_id) REFERENCES process_working_document(id),
    ADD CONSTRAINT ck_process_working_copy_kind
        CHECK (working_copy_kind IN ('SYSTEM_GENERATED', 'USER_WORKING_COPY')),
    ADD CONSTRAINT ck_process_working_verification_status
        CHECK (verification_status IN ('VERIFIED', 'REVIEW_REQUIRED', 'UNSUPPORTED')),
    ADD CONSTRAINT ck_process_working_version_positive CHECK (version_number >= 1),
    ADD UNIQUE KEY uq_process_working_copy_identity
        (baseline_document_id, source_sha256, conversion_version, working_copy_kind, working_sha256, registered_by),
    ADD KEY idx_process_working_owner (registered_by, baseline_document_id, created_at),
    ADD KEY idx_process_working_parent (previous_working_document_id);

UPDATE process_working_document
SET working_size_bytes = OCTET_LENGTH(content_bytes)
WHERE working_size_bytes = 0;

CREATE TABLE process_test_only_output (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    working_document_id BIGINT NOT NULL,
    registered_by VARCHAR(128) NOT NULL,
    artifact_kind VARCHAR(24) NOT NULL DEFAULT 'TEST_ONLY_OUTPUT',
    change_type VARCHAR(24) NOT NULL,
    baseline_source_sha256 CHAR(64) NOT NULL,
    input_working_sha256 CHAR(64) NOT NULL,
    output_sha256 CHAR(64) NOT NULL,
    output_filename VARCHAR(255) NOT NULL,
    output_size_bytes BIGINT NOT NULL,
    output_content LONGBLOB NOT NULL,
    artifact_status VARCHAR(24) NOT NULL DEFAULT 'TEST_ONLY',
    review_status VARCHAR(24) NOT NULL DEFAULT 'REVIEW_REQUIRED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_test_output_working_document
        FOREIGN KEY (working_document_id) REFERENCES process_working_document(id),
    CONSTRAINT ck_test_output_kind CHECK (artifact_kind IN ('TEST_ONLY_OUTPUT', 'SYNTHETIC_FIXTURE')),
    CONSTRAINT ck_test_output_change_type CHECK (change_type IN ('ADD', 'MODIFY', 'DELETE', 'PIPE_CHANGE', 'FLOW_CHANGE')),
    CONSTRAINT ck_test_output_status CHECK (artifact_status = 'TEST_ONLY'),
    CONSTRAINT ck_test_output_review_status CHECK (review_status IN ('REVIEW_REQUIRED', 'UNSUPPORTED')),
    KEY idx_test_output_owner (registered_by, created_at),
    KEY idx_test_output_working_copy (working_document_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER //
CREATE TRIGGER trg_process_test_only_output_no_update
BEFORE UPDATE ON process_test_only_output
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'TEST_ONLY output history is append-only';
END//

CREATE TRIGGER trg_process_test_only_output_no_delete
BEFORE DELETE ON process_test_only_output
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'TEST_ONLY output history is append-only';
END//
DELIMITER ;
