CREATE TABLE ocr_retry_commands (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    task_version BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ocr_retry_tenant_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_ocr_retry_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_ocr_retry_task FOREIGN KEY (task_id) REFERENCES ocr_tasks (id),
    CONSTRAINT fk_ocr_retry_user FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE INDEX idx_ocr_retry_task ON ocr_retry_commands (tenant_id, task_id, created_at);
