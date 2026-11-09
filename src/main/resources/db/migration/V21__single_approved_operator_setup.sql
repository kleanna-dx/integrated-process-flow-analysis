CREATE TABLE operator_account (
    id TINYINT NOT NULL PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_operator_account_singleton CHECK (id = 1),
    UNIQUE KEY uq_operator_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE operator_setup_request (
    request_id CHAR(36) NOT NULL PRIMARY KEY,
    browser_session_sha256 CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    approved_at DATETIME(6) NULL,
    approved_by VARCHAR(80) NULL,
    consumed_at DATETIME(6) NULL,
    CONSTRAINT ck_operator_setup_state CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CONSUMED')),
    KEY idx_operator_setup_state_expiry (state, expires_at),
    KEY idx_operator_setup_session (browser_session_sha256, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
