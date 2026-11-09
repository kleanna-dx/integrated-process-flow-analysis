ALTER TABLE drawing_object
    ADD COLUMN transform_x_emu BIGINT NULL,
    ADD COLUMN transform_y_emu BIGINT NULL,
    ADD COLUMN transform_width_emu BIGINT NULL,
    ADD COLUMN transform_height_emu BIGINT NULL,
    ADD COLUMN rotation_60000 INT NULL,
    ADD COLUMN flip_horizontal BOOLEAN NULL,
    ADD COLUMN flip_vertical BOOLEAN NULL;

ALTER TABLE process_relation
    ADD COLUMN connector_drawing_object_id BIGINT NULL,
    ADD COLUMN analysis_status VARCHAR(24) NOT NULL DEFAULT 'REVIEW_REQUIRED',
    ADD COLUMN reviewer_name VARCHAR(128) NULL,
    ADD COLUMN reviewed_at TIMESTAMP NULL,
    ADD CONSTRAINT fk_process_relation_connector_object
        FOREIGN KEY (connector_drawing_object_id) REFERENCES drawing_object(id),
    ADD CONSTRAINT ck_process_relation_analysis_status
        CHECK (analysis_status IN ('REVIEW_REQUIRED', 'UNANALYZABLE', 'MANUALLY_RESOLVED')),
    ADD UNIQUE KEY uq_process_relation_connector (connector_drawing_object_id);
