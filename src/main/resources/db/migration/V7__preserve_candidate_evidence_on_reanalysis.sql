ALTER TABLE process_relation
    ADD COLUMN candidate_evidence_at TIMESTAMP NULL,
    ADD COLUMN candidate_evidence_by VARCHAR(128) NULL,
    ADD COLUMN candidate_evidence_text LONGTEXT NULL;
