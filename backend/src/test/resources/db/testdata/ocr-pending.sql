INSERT INTO ocr_tasks (
    id, public_id, tenant_id, created_by, status, storage_key, original_filename,
    content_type, size_bytes, content_sha256, idempotency_key, request_hash,
    provider, provider_request_id, result_json, attempt_count, max_attempts,
    next_attempt_at, lease_until, last_error_code, last_error_message, started_at,
    completed_at, version, created_at, updated_at)
VALUES (
    1501, '00000000-0000-0000-0000-000000001501', 1001, 1101, 'PENDING',
    '1001/worker-fixture.jpg', 'worker-fixture.jpg', 'image/jpeg', 4,
    '32461d5bd1773012ac9f3f84abf7e2300c47b1677e7d8277a273ed9c2c89e6f3',
    'worker-fixture',
    '0000000000000000000000000000000000000000000000000000000000001501',
    NULL, NULL, NULL, 0, 3, CURRENT_TIMESTAMP, NULL, NULL, NULL, NULL, NULL,
    0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
