ALTER TABLE tenants
    ADD CONSTRAINT ck_tenants_required_text CHECK (
        CHAR_LENGTH(TRIM(public_id)) > 0
        AND CHAR_LENGTH(TRIM(name)) > 0
    );

ALTER TABLE tenants
    ADD CONSTRAINT ck_tenants_timestamps CHECK (updated_at >= created_at);

ALTER TABLE users
    ADD CONSTRAINT ck_users_profile_text CHECK (
        CHAR_LENGTH(TRIM(display_name)) > 0
        AND (union_id IS NULL OR CHAR_LENGTH(TRIM(union_id)) > 0)
    );

ALTER TABLE users
    ADD CONSTRAINT ck_users_timestamps CHECK (updated_at >= created_at);

ALTER TABLE auth_sessions
    ADD CONSTRAINT ck_auth_sessions_token_hash CHECK (CHAR_LENGTH(token_hash) = 64);

ALTER TABLE bill_code_sequences
    ADD CONSTRAINT ck_bill_code_sequences_name CHECK (CHAR_LENGTH(TRIM(sequence_name)) > 0);

ALTER TABLE bills
    ADD CONSTRAINT ck_bills_creation_idempotency CHECK (
        (creation_idempotency_key IS NULL AND creation_request_hash IS NULL)
        OR (creation_idempotency_key IS NOT NULL
            AND CHAR_LENGTH(TRIM(creation_idempotency_key)) > 0
            AND creation_request_hash IS NOT NULL
            AND CHAR_LENGTH(creation_request_hash) = 64)
    );

ALTER TABLE bills
    ADD CONSTRAINT ck_bills_dates CHECK (
        (due_date IS NULL OR due_date >= transport_date)
        AND updated_at >= created_at
        AND (deleted_at IS NULL OR deleted_at >= created_at)
    );

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_optional_text CHECK (
        (reference_no IS NULL OR CHAR_LENGTH(TRIM(reference_no)) > 0)
        AND (note IS NULL OR CHAR_LENGTH(TRIM(note)) > 0)
        AND (reversal_reason IS NULL OR CHAR_LENGTH(TRIM(reversal_reason)) > 0)
        AND (reversal_idempotency_key IS NULL OR CHAR_LENGTH(TRIM(reversal_idempotency_key)) > 0)
        AND (reversal_request_hash IS NULL OR CHAR_LENGTH(reversal_request_hash) = 64)
    );

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_reversal_time CHECK (
        reversed_at IS NULL OR reversed_at >= created_at
    );

ALTER TABLE ocr_tasks
    ADD CONSTRAINT ck_ocr_tasks_attempt_bounds CHECK (
        attempt_count <= max_attempts
        AND ((status = 'PENDING' AND attempt_count = 0)
            OR (status IN ('PROCESSING', 'RETRYING', 'SUCCEEDED', 'FAILED')))
        AND (status <> 'PROCESSING' OR (attempt_count > 0 AND started_at IS NOT NULL))
        AND (status <> 'RETRYING' OR attempt_count > 0)
    );

ALTER TABLE ocr_tasks
    ADD CONSTRAINT ck_ocr_tasks_terminal_metadata CHECK (
        (status <> 'SUCCEEDED'
            OR (provider IS NOT NULL
                AND CHAR_LENGTH(TRIM(provider)) > 0
                AND result_json IS NOT NULL))
        AND (status <> 'FAILED'
            OR (last_error_code IS NOT NULL
                AND CHAR_LENGTH(TRIM(last_error_code)) > 0
                AND last_error_message IS NOT NULL
                AND CHAR_LENGTH(TRIM(last_error_message)) > 0))
    );

ALTER TABLE ocr_tasks
    ADD CONSTRAINT ck_ocr_tasks_timestamps CHECK (
        updated_at >= created_at
        AND next_attempt_at >= created_at
        AND (started_at IS NULL OR started_at >= created_at)
        AND (lease_until IS NULL OR (started_at IS NOT NULL AND lease_until > started_at))
        AND (completed_at IS NULL OR completed_at >= created_at)
        AND (storage_cleanup_started_at IS NULL
            OR (completed_at IS NOT NULL AND storage_cleanup_started_at >= completed_at))
    );

ALTER TABLE ocr_retry_commands
    ADD CONSTRAINT ck_ocr_retry_commands_required_fields CHECK (
        CHAR_LENGTH(TRIM(idempotency_key)) > 0
        AND CHAR_LENGTH(request_hash) = 64
        AND task_version >= 0
    );

ALTER TABLE business_command_replays
    ADD CONSTRAINT ck_business_command_response CHECK (
        CHAR_LENGTH(TRIM(response_json)) > 0
    );

ALTER TABLE audit_events
    ADD CONSTRAINT ck_audit_events_required_text CHECK (
        CHAR_LENGTH(TRIM(action)) > 0
        AND CHAR_LENGTH(TRIM(aggregate_type)) > 0
        AND CHAR_LENGTH(TRIM(aggregate_id)) > 0
        AND (request_id IS NULL OR CHAR_LENGTH(TRIM(request_id)) > 0)
    );

ALTER TABLE shedlock
    ADD CONSTRAINT ck_shedlock_required_text CHECK (
        CHAR_LENGTH(TRIM(name)) > 0
        AND CHAR_LENGTH(TRIM(locked_by)) > 0
    );
