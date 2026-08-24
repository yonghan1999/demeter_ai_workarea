ALTER TABLE ocr_tasks
    ADD COLUMN storage_cleanup_started_at TIMESTAMP(6) NULL;

CREATE INDEX idx_ocr_storage_cleanup_work
    ON ocr_tasks (storage_deleted_at, storage_cleanup_started_at, completed_at, id);
