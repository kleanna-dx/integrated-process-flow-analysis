CREATE TABLE balance_evidence_review_snapshot (
    snapshot_id CHAR(64) NOT NULL,
    baseline_release_id BIGINT NOT NULL,
    baseline_version VARCHAR(32) NOT NULL,
    team_source_sha256 CHAR(64) NOT NULL,
    official_process_sha256 CHAR(64) NOT NULL,
    official_master_sha256 CHAR(64) NOT NULL,
    template_schema_version VARCHAR(16) NOT NULL,
    candidate_count INT NOT NULL,
    baseline_imbalance_summary_json LONGTEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (snapshot_id),
    CONSTRAINT fk_evidence_snapshot_baseline FOREIGN KEY (baseline_release_id)
        REFERENCES baseline_release(id),
    KEY idx_evidence_snapshot_baseline_created (baseline_release_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE balance_evidence_review_candidate (
    snapshot_id CHAR(64) NOT NULL,
    candidate_id VARCHAR(96) NOT NULL,
    source_row_number INT NOT NULL,
    official_drawing_file VARCHAR(255) NOT NULL,
    official_drawing_sheet VARCHAR(128) NOT NULL,
    source_equipment VARCHAR(128) NOT NULL,
    target_equipment VARCHAR(128) NOT NULL,
    source_cell_hint VARCHAR(512) NULL,
    target_cell_hint VARCHAR(512) NULL,
    connector_candidate_ids TEXT NULL,
    source_mass_raw VARCHAR(255) NULL,
    source_mass_value DECIMAL(24,8) NULL,
    source_material_type VARCHAR(128) NULL,
    official_flow_hints_json LONGTEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (snapshot_id, candidate_id),
    CONSTRAINT fk_evidence_candidate_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES balance_evidence_review_snapshot(snapshot_id),
    KEY idx_evidence_candidate_source_row (snapshot_id, source_row_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE balance_evidence_review_case
    MODIFY reviewed_quantity DECIMAL(24,8) NULL,
    MODIFY reviewed_unit VARCHAR(64) NULL,
    MODIFY reviewed_material_type VARCHAR(128) NULL,
    ADD COLUMN snapshot_id CHAR(64) NULL AFTER case_key,
    ADD COLUMN candidate_id VARCHAR(96) NULL AFTER snapshot_id,
    ADD COLUMN source_row_number INT NULL AFTER candidate_id,
    ADD COLUMN official_drawing_file VARCHAR(255) NULL AFTER source_row_number,
    ADD COLUMN official_drawing_sheet VARCHAR(128) NULL AFTER official_drawing_file,
    ADD COLUMN review_period VARCHAR(128) NULL AFTER reviewed_unit,
    ADD COLUMN physical_connection_confirmed BOOLEAN NULL AFTER review_period,
    ADD COLUMN evidence_type VARCHAR(48) NULL AFTER physical_connection_confirmed,
    ADD COLUMN evidence_identifier VARCHAR(255) NULL AFTER evidence_type,
    ADD COLUMN review_comment TEXT NULL AFTER evidence_text,
    ADD COLUMN review_cycle_candidate BOOLEAN NULL AFTER review_comment,
    ADD COLUMN review_state VARCHAR(32) NULL AFTER review_status,
    ADD COLUMN review_version BIGINT NOT NULL DEFAULT 1 AFTER review_state,
    ADD COLUMN last_upload_sha256 CHAR(64) NULL AFTER review_version,
    ADD UNIQUE KEY uq_balance_evidence_review_snapshot_candidate (snapshot_id, candidate_id),
    ADD CONSTRAINT ck_balance_evidence_review_state CHECK (review_state IS NULL OR review_state IN
        ('REVIEWED_PROVISIONAL', 'EVIDENCE_VERIFIED', 'REJECTED', 'NEEDS_MORE_INFO'));

ALTER TABLE balance_evidence_review_history
    ADD COLUMN candidate_id VARCHAR(96) NULL AFTER case_key,
    ADD COLUMN upload_sha256 CHAR(64) NULL AFTER actor_source,
    ADD COLUMN review_version BIGINT NULL AFTER upload_sha256;
