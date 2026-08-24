package com.demeter.backend;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = {
        "classpath:db/testdata/cleanup.sql",
        "classpath:db/testdata/bills-basic.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DatabaseIntegrityIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void rejectsAPaymentThatPointsToAnotherTenantsBill() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO payments (
                            tenant_id, bill_id, amount, method, paid_at,
                            idempotency_key, request_hash, status, version, created_by, created_at)
                        VALUES (1002, 1201, 100.00, 'CASH', CURRENT_TIMESTAMP,
                                'cross-tenant-payment',
                                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                                'ACTIVE', 0, 1102, CURRENT_TIMESTAMP)
                        """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsALedgerStatusThatDoesNotMatchTheRecordedAmount() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE bills SET status = 'PAID' WHERE id = 1201"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAnIncompletePaymentReversal() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE payments SET status = 'REVERSED' WHERE id = 1401"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAnIncompleteSoftDelete() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE bills SET deleted_at = CURRENT_TIMESTAMP WHERE id = 1201"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAnAuditActorFromAnotherTenant() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO audit_events (
                            tenant_id, actor_user_id, action, aggregate_type,
                            aggregate_id, created_at)
                        VALUES (1001, 1102, 'INVALID', 'BILL', '1201', CURRENT_TIMESTAMP)
                        """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAnOcrStorageDeletionWithoutACleanupClaim() {
        jdbcTemplate.update(
                """
                INSERT INTO ocr_tasks (
                    id, public_id, tenant_id, created_by, status, storage_key, content_type,
                    size_bytes, content_sha256, idempotency_key, request_hash, attempt_count,
                    max_attempts, next_attempt_at, last_error_code, last_error_message,
                    completed_at, version, created_at, updated_at)
                VALUES (1501, '00000000-0000-0000-0000-000000001501', 1001, 1101, 'FAILED',
                    '1001/test.jpg', 'image/jpeg', 4,
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'integrity-ocr',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    1, 3, CURRENT_TIMESTAMP, 'TEST_FAILURE', 'test failure', CURRENT_TIMESTAMP, 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE ocr_tasks SET storage_deleted_at = CURRENT_TIMESTAMP WHERE id = 1501"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAnOrphanedBillCreationIdempotencyKey() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE bills SET creation_idempotency_key = 'orphan-key' WHERE id = 1201"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsADueDateBeforeTheTransportDate() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE bills SET due_date = '2024-05-19' WHERE id = 1201"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAnOcrAttemptCountAboveItsMaximum() {
        jdbcTemplate.update(
                """
                INSERT INTO ocr_tasks (
                    id, public_id, tenant_id, created_by, status, storage_key, content_type,
                    size_bytes, content_sha256, idempotency_key, request_hash, attempt_count,
                    max_attempts, next_attempt_at, version, created_at, updated_at)
                VALUES (1502, '00000000-0000-0000-0000-000000001502', 1001, 1101, 'PENDING',
                    '1001/test-1502.jpg', 'image/jpeg', 4,
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'integrity-ocr-1502',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    0, 3, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE ocr_tasks SET attempt_count = 4 WHERE id = 1502"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
