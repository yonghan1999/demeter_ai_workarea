CREATE TABLE business_command_replays (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    operation_name VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_business_command_replay UNIQUE (tenant_id, operation_name, idempotency_key),
    CONSTRAINT fk_business_command_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_business_command_user FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE INDEX idx_business_command_created
    ON business_command_replays (tenant_id, created_at);
