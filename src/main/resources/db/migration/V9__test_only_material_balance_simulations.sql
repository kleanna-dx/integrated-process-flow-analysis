CREATE TABLE material_balance_simulation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    simulation_id VARCHAR(96) NOT NULL,
    scenario_name VARCHAR(255) NOT NULL,
    scenario_mode VARCHAR(16) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    result_json LONGTEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_material_balance_simulation_id UNIQUE (simulation_id),
    CONSTRAINT ck_material_balance_simulation_mode CHECK (scenario_mode = 'TEST_ONLY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE material_balance_transfer (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    simulation_db_id BIGINT NOT NULL,
    transfer_id VARCHAR(128) NOT NULL,
    relation_mode VARCHAR(16) NOT NULL,
    relation_id BIGINT NULL,
    source_node_id VARCHAR(128) NULL,
    target_node_id VARCHAR(128) NULL,
    edge_type VARCHAR(40) NULL,
    flow_type VARCHAR(24) NULL,
    quantity DECIMAL(20,6) NULL,
    quantity_unit VARCHAR(64) NULL,
    evidence_text VARCHAR(1024) NULL,
    included BOOLEAN NOT NULL,
    exclusion_reason VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_material_balance_transfer_id UNIQUE (transfer_id),
    CONSTRAINT fk_material_balance_transfer_simulation FOREIGN KEY (simulation_db_id)
        REFERENCES material_balance_simulation(id),
    CONSTRAINT fk_material_balance_transfer_relation FOREIGN KEY (relation_id)
        REFERENCES process_relation(id),
    CONSTRAINT ck_material_balance_transfer_mode CHECK (relation_mode IN ('TEST_ONLY', 'CONFIRMED')),
    CONSTRAINT ck_material_balance_transfer_flow_type CHECK (flow_type IS NULL OR flow_type IN ('WASTEWATER', 'WASTE')),
    CONSTRAINT ck_material_balance_transfer_included CHECK (included = TRUE OR exclusion_reason IS NOT NULL),
    KEY idx_material_balance_transfer_simulation (simulation_db_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
