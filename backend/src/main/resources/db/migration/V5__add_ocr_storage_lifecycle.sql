ALTER TABLE ocr_tasks
    ADD COLUMN storage_deleted_at TIMESTAMP(6) NULL;

CREATE INDEX idx_ocr_tasks_storage_cleanup
    ON ocr_tasks (status, completed_at, storage_deleted_at);
