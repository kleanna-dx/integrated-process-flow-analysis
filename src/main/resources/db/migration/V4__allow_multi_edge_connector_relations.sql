ALTER TABLE process_relation
    ADD INDEX idx_process_relation_connector (connector_drawing_object_id);

ALTER TABLE process_relation
    DROP INDEX uq_process_relation_connector,
    ADD COLUMN connector_analysis_key VARCHAR(192) NULL,
    ADD UNIQUE KEY uq_process_relation_analysis_key (connector_analysis_key);
