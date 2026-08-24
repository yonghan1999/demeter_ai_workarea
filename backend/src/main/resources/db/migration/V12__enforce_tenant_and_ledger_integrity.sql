ALTER TABLE users
    ADD CONSTRAINT uk_users_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE bills
    ADD CONSTRAINT uk_bills_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ocr_tasks
    ADD CONSTRAINT uk_ocr_tasks_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE bills
    ADD CONSTRAINT fk_bills_created_by_tenant
        FOREIGN KEY (tenant_id, created_by) REFERENCES users (tenant_id, id);

ALTER TABLE bills
    ADD CONSTRAINT fk_bills_updated_by_tenant
        FOREIGN KEY (tenant_id, updated_by) REFERENCES users (tenant_id, id);

ALTER TABLE bills
    ADD CONSTRAINT fk_bills_deleted_by_tenant
        FOREIGN KEY (tenant_id, deleted_by) REFERENCES users (tenant_id, id);

ALTER TABLE bills
    ADD CONSTRAINT ck_bills_payment_state CHECK (
        (paid_amount = 0 AND status = 'UNPAID')
        OR (paid_amount > 0 AND paid_amount < amount AND status = 'PARTIALLY_PAID')
        OR (paid_amount = amount AND status = 'PAID')
    );

ALTER TABLE bills
    ADD CONSTRAINT ck_bills_deletion_state CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL AND delete_reason IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL AND delete_reason IS NOT NULL)
    );

ALTER TABLE payments
    ADD CONSTRAINT fk_payments_bill_tenant
        FOREIGN KEY (tenant_id, bill_id) REFERENCES bills (tenant_id, id);

ALTER TABLE payments
    ADD CONSTRAINT fk_payments_created_by_tenant
        FOREIGN KEY (tenant_id, created_by) REFERENCES users (tenant_id, id);

ALTER TABLE payments
    ADD CONSTRAINT fk_payments_reversed_by_tenant
        FOREIGN KEY (tenant_id, reversed_by) REFERENCES users (tenant_id, id);

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_reversal_state CHECK (
        (status = 'ACTIVE'
            AND reversed_at IS NULL
            AND reversed_by IS NULL
            AND reversal_reason IS NULL
            AND reversal_idempotency_key IS NULL
            AND reversal_request_hash IS NULL)
        OR (status = 'REVERSED'
            AND reversed_at IS NOT NULL
            AND reversed_by IS NOT NULL
            AND reversal_reason IS NOT NULL
            AND reversal_idempotency_key IS NOT NULL
            AND reversal_request_hash IS NOT NULL)
    );

ALTER TABLE ocr_tasks
    ADD CONSTRAINT fk_ocr_tasks_created_by_tenant
        FOREIGN KEY (tenant_id, created_by) REFERENCES users (tenant_id, id);

ALTER TABLE ocr_tasks
    ADD CONSTRAINT ck_ocr_tasks_lifecycle CHECK (
        (status = 'PROCESSING' AND lease_until IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('PENDING', 'RETRYING') AND lease_until IS NULL AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND lease_until IS NULL AND completed_at IS NOT NULL)
    );

ALTER TABLE ocr_retry_commands
    ADD CONSTRAINT fk_ocr_retry_task_tenant
        FOREIGN KEY (tenant_id, task_id) REFERENCES ocr_tasks (tenant_id, id);

ALTER TABLE ocr_retry_commands
    ADD CONSTRAINT fk_ocr_retry_user_tenant
        FOREIGN KEY (tenant_id, created_by) REFERENCES users (tenant_id, id);

ALTER TABLE business_command_replays
    ADD CONSTRAINT fk_business_command_user_tenant
        FOREIGN KEY (tenant_id, created_by) REFERENCES users (tenant_id, id);

ALTER TABLE audit_events
    ADD CONSTRAINT fk_audit_events_actor_tenant
        FOREIGN KEY (tenant_id, actor_user_id) REFERENCES users (tenant_id, id);
