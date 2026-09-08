CREATE TABLE admin_sessions (
    id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_admin_sessions_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_admin_sessions_cleanup
    ON admin_sessions (expires_at, revoked_at);
