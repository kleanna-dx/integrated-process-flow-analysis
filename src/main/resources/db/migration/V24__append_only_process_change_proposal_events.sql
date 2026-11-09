CREATE TABLE process_change_proposal_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    proposal_id CHAR(36) NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    change_type VARCHAR(24) NOT NULL,
    process_code VARCHAR(32) NOT NULL,
    sheet_name VARCHAR(128) NOT NULL,
    working_document_id BIGINT NOT NULL,
    working_sha256 CHAR(64) NOT NULL,
    request_user VARCHAR(128) NOT NULL,
    equipment_management_no VARCHAR(96) NULL,
    before_json LONGTEXT NULL,
    after_json LONGTEXT NULL,
    rationale LONGTEXT NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    mapping_status VARCHAR(32) NOT NULL DEFAULT 'REVIEW_REQUIRED',
    calculation_status VARCHAR(40) NOT NULL DEFAULT 'CALCULATION_UNAVAILABLE',
    review_status VARCHAR(32) NOT NULL DEFAULT 'REVIEW_REQUIRED',
    test_only_excel_created BOOLEAN NOT NULL DEFAULT FALSE,
    output_sha256 CHAR(64) NULL,
    validation_result_json LONGTEXT NULL,
    failure_reason LONGTEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_process_change_proposal_working_copy
        FOREIGN KEY (working_document_id) REFERENCES process_working_document(id),
    CONSTRAINT ck_process_change_proposal_event_type
        CHECK (event_type IN ('PROPOSED', 'REVIEW_NOTE', 'TEST_ONLY_OUTPUT', 'VALIDATION_RESULT')),
    CONSTRAINT ck_process_change_proposal_type
        CHECK (change_type IN ('ADD', 'MODIFY', 'DELETE', 'PIPE_CHANGE', 'FLOW_CHANGE')),
    CONSTRAINT ck_process_change_proposal_calculation
        CHECK (calculation_status IN ('CALCULATION_UNAVAILABLE', 'CALCULATED_SYNTHETIC', 'NOT_REQUESTED')),
    CONSTRAINT ck_process_change_proposal_review
        CHECK (review_status IN ('REVIEW_REQUIRED', 'REJECTED')),
    CONSTRAINT ck_process_change_proposal_evidence_json
        CHECK (JSON_VALID(evidence_json)),
    KEY idx_process_change_proposal_id_event (proposal_id, event_id),
    KEY idx_process_change_proposal_user_time (request_user, created_at),
    KEY idx_process_change_proposal_scope (process_code, sheet_name, created_at),
    KEY idx_process_change_proposal_working_copy (working_document_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER //
CREATE TRIGGER trg_process_change_proposal_event_no_update
BEFORE UPDATE ON process_change_proposal_event
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Process change proposal events are append-only';
END//

CREATE TRIGGER trg_process_change_proposal_event_no_delete
BEFORE DELETE ON process_change_proposal_event
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Process change proposal events are append-only';
END//
DELIMITER ;
