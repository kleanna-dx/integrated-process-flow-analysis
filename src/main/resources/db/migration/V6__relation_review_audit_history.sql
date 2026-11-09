CREATE TABLE process_relation_review_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    process_relation_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    actor_name VARCHAR(128) NOT NULL,
    reviewer_name VARCHAR(128) NULL,
    previous_snapshot_json LONGTEXT NULL,
    resulting_snapshot_json LONGTEXT NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_relation_review_history_relation
        FOREIGN KEY (process_relation_id) REFERENCES process_relation(id),
    CONSTRAINT ck_relation_review_history_action
        CHECK (action_type IN ('CANDIDATE_EVIDENCE_ADDED', 'RELATION_CREATED', 'RELATION_UPDATED')),
    KEY idx_relation_review_history_relation_time (process_relation_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
