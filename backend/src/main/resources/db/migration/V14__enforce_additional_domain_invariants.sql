ALTER TABLE users
    ADD CONSTRAINT ck_users_open_id_not_blank CHECK (CHAR_LENGTH(TRIM(open_id)) > 0);

ALTER TABLE auth_sessions
    ADD CONSTRAINT ck_auth_sessions_lifecycle CHECK (
        expires_at > created_at
        AND (revoked_at IS NULL OR revoked_at >= created_at)
    );

ALTER TABLE bill_code_sequences
    ADD CONSTRAINT ck_bill_code_sequences_next_value CHECK (next_value > 0);

ALTER TABLE bills
    ADD CONSTRAINT ck_bills_required_text CHECK (
        CHAR_LENGTH(TRIM(code)) > 0
        AND CHAR_LENGTH(TRIM(shipper)) > 0
        AND CHAR_LENGTH(TRIM(shipper_normalized)) > 0
        AND CHAR_LENGTH(TRIM(origin)) > 0
        AND CHAR_LENGTH(TRIM(destination)) > 0
    );

ALTER TABLE bill_tags
    ADD CONSTRAINT ck_bill_tags_not_blank CHECK (CHAR_LENGTH(TRIM(tag)) > 0);

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_required_text CHECK (
        CHAR_LENGTH(TRIM(idempotency_key)) > 0
        AND CHAR_LENGTH(request_hash) = 64
    );

ALTER TABLE ocr_tasks
    ADD CONSTRAINT ck_ocr_tasks_required_text CHECK (
        CHAR_LENGTH(TRIM(public_id)) > 0
        AND CHAR_LENGTH(TRIM(storage_key)) > 0
        AND CHAR_LENGTH(TRIM(content_type)) > 0
        AND CHAR_LENGTH(content_sha256) = 64
        AND CHAR_LENGTH(TRIM(idempotency_key)) > 0
        AND CHAR_LENGTH(request_hash) = 64
    );

ALTER TABLE ocr_tasks
    ADD CONSTRAINT ck_ocr_tasks_storage_cleanup_state CHECK (
        storage_deleted_at IS NULL
        OR (storage_cleanup_started_at IS NOT NULL
            AND storage_deleted_at >= storage_cleanup_started_at)
    );

ALTER TABLE business_command_replays
    ADD CONSTRAINT ck_business_command_replays_required_text CHECK (
        CHAR_LENGTH(TRIM(operation_name)) > 0
        AND CHAR_LENGTH(TRIM(idempotency_key)) > 0
        AND CHAR_LENGTH(request_hash) = 64
    );
