ALTER TABLE bills
    ADD COLUMN paid_amount DECIMAL(12, 2) NOT NULL DEFAULT 0;

UPDATE bills
SET paid_amount = amount
WHERE status = 'PAID';

ALTER TABLE bills
    ADD CONSTRAINT ck_bills_paid_amount CHECK (paid_amount >= 0 AND paid_amount <= amount);

CREATE TABLE payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    bill_id BIGINT NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    method VARCHAR(24) NOT NULL,
    paid_at TIMESTAMP(6) NOT NULL,
    reference_no VARCHAR(120) NULL,
    note VARCHAR(240) NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    reversed_at TIMESTAMP(6) NULL,
    reversed_by BIGINT NULL,
    reversal_reason VARCHAR(240) NULL,
    reversal_idempotency_key VARCHAR(128) NULL,
    reversal_request_hash VARCHAR(64) NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payments_tenant_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uk_payments_tenant_reversal_idempotency UNIQUE (tenant_id, reversal_idempotency_key),
    CONSTRAINT fk_payments_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_payments_bill FOREIGN KEY (bill_id) REFERENCES bills (id),
    CONSTRAINT fk_payments_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_payments_reversed_by FOREIGN KEY (reversed_by) REFERENCES users (id),
    CONSTRAINT ck_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_payments_method CHECK (method IN ('CASH', 'BANK_TRANSFER', 'WECHAT', 'ALIPAY', 'OTHER')),
    CONSTRAINT ck_payments_status CHECK (status IN ('ACTIVE', 'REVERSED'))
);

CREATE INDEX idx_payments_bill_created ON payments (tenant_id, bill_id, created_at);
CREATE INDEX idx_payments_paid_at ON payments (tenant_id, paid_at);

INSERT INTO payments (
    tenant_id, bill_id, amount, method, paid_at, reference_no, note,
    idempotency_key, request_hash, status, version, created_by, created_at)
SELECT
    tenant_id,
    id,
    amount,
    'OTHER',
    updated_at,
    NULL,
    'V2 migration: imported paid bill',
    CONCAT('migration-v2-bill-', id),
    '0000000000000000000000000000000000000000000000000000000000000000',
    'ACTIVE',
    0,
    updated_by,
    updated_at
FROM bills
WHERE status = 'PAID' AND paid_amount > 0;
