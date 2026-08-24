CREATE INDEX idx_bills_tenant_active_status_date
    ON bills (tenant_id, deleted_at, status, transport_date, id);

CREATE INDEX idx_bills_tenant_active_date
    ON bills (tenant_id, deleted_at, transport_date, id);

CREATE INDEX idx_payments_bill_status
    ON payments (tenant_id, bill_id, status);

CREATE INDEX idx_ocr_tasks_tenant_status_created
    ON ocr_tasks (tenant_id, status, created_at, id);

CREATE INDEX idx_ocr_tasks_queue_created
    ON ocr_tasks (status, next_attempt_at, created_at, id);

CREATE INDEX idx_ocr_retry_created
    ON ocr_retry_commands (created_at, id);

CREATE INDEX idx_business_command_cleanup
    ON business_command_replays (created_at, id);
