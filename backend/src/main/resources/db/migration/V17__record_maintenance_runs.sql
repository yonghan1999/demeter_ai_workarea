CREATE TABLE maintenance_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_type VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    duration_ms BIGINT NULL,
    deleted_sessions INT NOT NULL DEFAULT 0,
    deleted_command_replays INT NOT NULL DEFAULT 0,
    deleted_ocr_retry_commands INT NOT NULL DEFAULT 0,
    deleted_ocr_documents INT NOT NULL DEFAULT 0,
    deleted_ocr_orphans INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    failure_summary VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_maintenance_runs_type CHECK (CHAR_LENGTH(TRIM(run_type)) > 0),
    CONSTRAINT ck_maintenance_runs_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL_FAILURE', 'FAILED')),
    CONSTRAINT ck_maintenance_runs_counts CHECK (
        deleted_sessions >= 0
        AND deleted_command_replays >= 0
        AND deleted_ocr_retry_commands >= 0
        AND deleted_ocr_documents >= 0
        AND deleted_ocr_orphans >= 0
        AND failure_count >= 0
    ),
    CONSTRAINT ck_maintenance_runs_completion CHECK (
        (status = 'RUNNING' AND completed_at IS NULL)
        OR (status <> 'RUNNING' AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idx_maintenance_runs_type_started
    ON maintenance_runs (run_type, started_at);
