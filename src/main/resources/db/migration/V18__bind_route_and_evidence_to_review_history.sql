ALTER TABLE balance_evidence_review_history
    ADD UNIQUE KEY uq_step7_review_history_identity (id, case_key, candidate_id, review_version);

ALTER TABLE balance_evidence_review_route
    MODIFY case_key VARCHAR(96) NOT NULL,
    ADD KEY idx_step7_route_history_id (review_history_id, case_key, candidate_id, review_version),
    ADD CONSTRAINT fk_step7_route_review_case FOREIGN KEY (snapshot_id, candidate_id)
        REFERENCES balance_evidence_review_case(snapshot_id, candidate_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_step7_route_review_history FOREIGN KEY (review_history_id, case_key, candidate_id, review_version)
        REFERENCES balance_evidence_review_history(id, case_key, candidate_id, review_version)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_step7_route_review_state CHECK (route_review_state IN
        ('ROUTE_DETAILS_RECORDED', 'NEEDS_MORE_INFO'));

ALTER TABLE balance_evidence_provenance_history
    MODIFY case_key VARCHAR(96) NOT NULL,
    ADD KEY idx_step7_provenance_review_history_id (review_history_id, case_key, candidate_id, review_version),
    ADD CONSTRAINT fk_step7_provenance_review_case FOREIGN KEY (snapshot_id, candidate_id)
        REFERENCES balance_evidence_review_case(snapshot_id, candidate_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_step7_provenance_review_history FOREIGN KEY (review_history_id, case_key, candidate_id, review_version)
        REFERENCES balance_evidence_review_history(id, case_key, candidate_id, review_version)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_step7_provenance_state CHECK (provenance_state =
        'EVIDENCE_TYPED_RECORDED_NOT_OFFICIAL_APPROVAL');
