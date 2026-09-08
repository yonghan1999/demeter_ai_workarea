CREATE TABLE admin_command_replays (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operation_name VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_admin_command_replay UNIQUE (operation_name, idempotency_key)
);

CREATE INDEX idx_admin_command_replay_created
    ON admin_command_replays (created_at);
