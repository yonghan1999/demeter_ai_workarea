ALTER TABLE bills
    ADD COLUMN creation_idempotency_key VARCHAR(128) NULL;

ALTER TABLE bills
    ADD COLUMN creation_request_hash VARCHAR(64) NULL;

CREATE UNIQUE INDEX uk_bills_tenant_creation_idempotency
    ON bills (tenant_id, creation_idempotency_key);
