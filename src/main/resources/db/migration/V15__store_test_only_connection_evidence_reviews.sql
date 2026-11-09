CREATE TABLE balance_evidence_review_case (
    id BIGINT NOT NULL AUTO_INCREMENT,
    case_key VARCHAR(96) NOT NULL,
    baseline_release_id BIGINT NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    source_equipment VARCHAR(128) NOT NULL,
    target_equipment VARCHAR(128) NOT NULL,
    reviewed_quantity DECIMAL(24,8) NOT NULL,
    reviewed_unit VARCHAR(64) NOT NULL,
    reviewed_material_type VARCHAR(128) NOT NULL,
    reviewed_flow_type VARCHAR(64) NULL,
    evidence_text TEXT NOT NULL,
    review_status VARCHAR(48) NOT NULL,
    reviewer_name_declared VARCHAR(128) NOT NULL,
    reviewed_date DATE NOT NULL,
    review_snapshot_json JSON NOT NULL,
    official_approval_created BOOLEAN NOT NULL DEFAULT FALSE,
    imported_by VARCHAR(48) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_balance_evidence_review_case_key (case_key),
    KEY idx_balance_evidence_review_baseline_status (baseline_release_id, review_status),
    CONSTRAINT fk_balance_evidence_review_baseline FOREIGN KEY (baseline_release_id) REFERENCES baseline_release(id),
    CONSTRAINT ck_balance_evidence_review_status CHECK (review_status IN
        ('TEST_ONLY_EVIDENCE_REVIEWED', 'NEEDS_MORE_EVIDENCE', 'REJECTED')),
    CONSTRAINT ck_balance_evidence_review_not_approval CHECK (official_approval_created = FALSE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE balance_evidence_review_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    case_key VARCHAR(96) NOT NULL,
    action_code VARCHAR(24) NOT NULL,
    before_json JSON NULL,
    after_json JSON NOT NULL,
    actor_source VARCHAR(48) NOT NULL,
    event_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_balance_evidence_review_history_event (event_sha256),
    KEY idx_balance_evidence_review_history_case (case_key, id),
    CONSTRAINT fk_balance_evidence_review_history_case FOREIGN KEY (case_key)
        REFERENCES balance_evidence_review_case(case_key),
    CONSTRAINT ck_balance_evidence_review_history_action CHECK (action_code IN ('CREATED', 'UPDATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
