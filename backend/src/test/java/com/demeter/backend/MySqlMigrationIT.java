package com.demeter.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demeter.backend.ocr.infrastructure.OcrTaskRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "demeter.ocr.worker.enabled=false",
        "demeter.maintenance.enabled=false",
        "demeter.payment.reconciliation.enabled=false",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class MySqlMigrationIT {

    private static final String MYSQL_IMAGE =
            "mysql:8.4@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb";
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("demeter")
            .withUsername("demeter")
            .withPassword("demeter-test-password")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    OcrTaskRepository taskRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void appliesAllVersionedMigrationsOnMySql84() {
        assertThat(jdbcTemplate.queryForObject("SELECT VERSION()", String.class)).startsWith("8.4.");
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = 1 AND version IS NOT NULL
                        ORDER BY installed_rank
                        """,
                        String.class))
                .containsExactlyElementsOf(expectedMigrationVersions());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0",
                        Integer.class))
                .isZero();
    }

    @Test
    void enforcesTenantLedgerAndDomainInvariantsOnMySql() {
        Instant now = resetFixtures();

        assertConstraintViolation(() -> jdbcTemplate.update(
                """
                INSERT INTO users (
                    id, tenant_id, open_id, display_name, status, version, created_at, updated_at)
                VALUES (3, 1, '   ', '非法用户', 'ACTIVE', 0, ?, ?)
                """,
                Timestamp.from(now),
                Timestamp.from(now)));

        assertConstraintViolation(() -> jdbcTemplate.update(
                """
                INSERT INTO auth_sessions (id, user_id, token_hash, expires_at, created_at)
                VALUES ('00000000-0000-0000-0000-000000000001', 1, ?, ?, ?)
                """,
                HASH_A,
                Timestamp.from(now),
                Timestamp.from(now)));

        assertConstraintViolation(() -> jdbcTemplate.update(
                "INSERT INTO bill_code_sequences (tenant_id, sequence_name, next_value) VALUES (1, 'bill', 0)"));

        insertBill(now);
        assertConstraintViolation(() -> jdbcTemplate.update("UPDATE bills SET shipper = '   ' WHERE id = 10"));
        assertConstraintViolation(() -> jdbcTemplate.update(
                "INSERT INTO bill_tags (bill_id, tag) VALUES (10, '   ')"));
        assertConstraintViolation(() -> jdbcTemplate.update(
                "UPDATE bills SET due_date = '2026-08-21' WHERE id = 10"));
        assertConstraintViolation(() -> jdbcTemplate.update(
                "UPDATE bills SET creation_idempotency_key = 'orphan-key' WHERE id = 10"));
        assertConstraintViolation(() -> jdbcTemplate.update(
                "UPDATE bills SET status = 'PAID' WHERE id = 10"));
        assertConstraintViolation(() -> jdbcTemplate.update(
                "UPDATE bills SET deleted_at = ?, deleted_by = NULL, delete_reason = NULL WHERE id = 10",
                Timestamp.from(now)));

        assertConstraintViolation(() -> jdbcTemplate.update(
                """
                INSERT INTO payments (
                    tenant_id, bill_id, amount, method, paid_at, idempotency_key,
                    request_hash, status, version, created_by, created_at)
                VALUES (2, 10, 100.00, 'CASH', ?, 'cross-tenant-payment', ?, 'ACTIVE', 0, 2, ?)
                """,
                Timestamp.from(now),
                HASH_A,
                Timestamp.from(now)));
        assertConstraintViolation(() -> jdbcTemplate.update(
                """
                INSERT INTO payments (
                    tenant_id, bill_id, amount, method, paid_at, idempotency_key,
                    request_hash, status, version, created_by, created_at)
                VALUES (1, 10, 100.00, 'CASH', ?, '   ', ?, 'ACTIVE', 0, 1, ?)
                """,
                Timestamp.from(now),
                HASH_A,
                Timestamp.from(now)));

        insertOcrTask(101, "00000000-0000-0000-0000-000000000101", now, now);
        assertConstraintViolation(() -> jdbcTemplate.update(
                "UPDATE ocr_tasks SET storage_deleted_at = ? WHERE id = 101",
                Timestamp.from(now)));
        assertConstraintViolation(() -> jdbcTemplate.update(
                "UPDATE ocr_tasks SET attempt_count = 4 WHERE id = 101"));
        assertConstraintViolation(() -> jdbcTemplate.update(
                """
                INSERT INTO ocr_tasks (
                    public_id, tenant_id, created_by, status, storage_key, content_type,
                    size_bytes, content_sha256, idempotency_key, request_hash,
                    attempt_count, max_attempts, next_attempt_at, version, created_at, updated_at)
                VALUES ('00000000-0000-0000-0000-000000000102', 1, 1, 'PENDING', '   ',
                        'image/png', 10, ?, 'invalid-storage-key', ?, 0, 3, ?, 0, ?, ?)
                """,
                HASH_A,
                HASH_B,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now)));

        assertConstraintViolation(() -> jdbcTemplate.update(
                """
                INSERT INTO business_command_replays (
                    tenant_id, operation_name, idempotency_key, request_hash,
                    response_json, created_by, created_at)
                VALUES (1, '   ', 'command-001', ?, '{}', 1, ?)
                """,
                HASH_A,
                Timestamp.from(now)));
    }

    @Test
    void skipLockedClaimsTheNextReadyOcrTaskWithoutWaiting() throws Exception {
        Instant now = resetFixtures();
        insertOcrTask(
                101,
                "00000000-0000-0000-0000-000000000101",
                now.minus(2, ChronoUnit.MINUTES),
                now.minus(2, ChronoUnit.MINUTES));
        insertOcrTask(
                102,
                "00000000-0000-0000-0000-000000000102",
                now.minus(1, ChronoUnit.MINUTES),
                now.minus(1, ChronoUnit.MINUTES));

        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    Long lockedId = jdbcTemplate.queryForObject(
                            "SELECT id FROM ocr_tasks WHERE id = 101 FOR UPDATE",
                            Long.class);
                    assertThat(lockedId).isEqualTo(101L);
                    rowLocked.countDown();
                    await(releaseLock);
                }));

        try {
            assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Long claimedId = new TransactionTemplate(transactionManager).execute(status -> taskRepository
                    .findNextClaimableForUpdate(Instant.now())
                    .orElseThrow()
                    .getId());

            assertThat(claimedId).isEqualTo(102L);
        } finally {
            releaseLock.countDown();
            lockHolder.get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    void protectsAppendOnlyLedgerAuditAndOcrSourceMetadata() {
        Instant now = resetFixtures();
        insertBill(now);
        insertPayment(now);
        jdbcTemplate.update(
                """
                UPDATE bills
                SET paid_amount = 100.00, status = 'PARTIALLY_PAID', version = version + 1,
                    updated_by = 1, updated_at = ?
                WHERE id = 10
                """,
                Timestamp.from(now));

        assertDatabaseRejected(() -> jdbcTemplate.update("UPDATE payments SET amount = 99.00 WHERE id = 20"));
        assertDatabaseRejected(() -> jdbcTemplate.update("UPDATE payments SET tenant_id = 2 WHERE id = 20"));
        assertDatabaseRejected(() -> jdbcTemplate.update("UPDATE payments SET bill_id = 11 WHERE id = 20"));
        assertDatabaseRejected(() -> jdbcTemplate.update("UPDATE payments SET method = 'WECHAT' WHERE id = 20"));
        assertDatabaseRejected(() -> jdbcTemplate.update("DELETE FROM payments WHERE id = 20"));
        assertDatabaseRejected(() -> jdbcTemplate.update(
                "UPDATE bills SET paid_amount = 0.00, status = 'UNPAID' WHERE id = 10"));

        jdbcTemplate.update(
                """
                UPDATE payments
                SET status = 'REVERSED', version = version + 1, reversed_at = ?, reversed_by = 1,
                    reversal_reason = '录入错误', reversal_idempotency_key = 'reverse-payment-20',
                    reversal_request_hash = ?
                WHERE id = 20
                """,
                Timestamp.from(now.plusSeconds(1)),
                HASH_B);
        jdbcTemplate.update(
                """
                UPDATE bills
                SET paid_amount = 0.00, status = 'UNPAID', version = version + 1,
                    updated_by = 1, updated_at = ?
                WHERE id = 10
                """,
                Timestamp.from(now.plusSeconds(1)));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = 20", String.class))
                .isEqualTo("REVERSED");
        assertThat(jdbcTemplate.queryForObject("SELECT paid_amount FROM bills WHERE id = 10", java.math.BigDecimal.class))
                .isEqualByComparingTo("0.00");
        assertDatabaseRejected(() -> jdbcTemplate.update(
                """
                UPDATE payments
                SET status = 'ACTIVE', version = version + 1, reversed_at = NULL, reversed_by = NULL,
                    reversal_reason = NULL, reversal_idempotency_key = NULL, reversal_request_hash = NULL
                WHERE id = 20
                """));

        jdbcTemplate.update(
                """
                INSERT INTO audit_events (
                    id, tenant_id, actor_user_id, action, aggregate_type, aggregate_id,
                    request_id, details, created_at)
                VALUES (30, 1, 1, 'PAYMENT_REVERSED', 'PAYMENT', '20', 'request-30', '{}', ?)
                """,
                Timestamp.from(now));
        assertDatabaseRejected(() -> jdbcTemplate.update(
                "UPDATE audit_events SET details = '{\"tampered\":true}' WHERE id = 30"));
        assertDatabaseRejected(() -> jdbcTemplate.update("DELETE FROM audit_events WHERE id = 30"));

        insertOcrTask(101, "00000000-0000-0000-0000-000000000101", now, now);
        assertDatabaseRejected(() -> jdbcTemplate.update(
                "UPDATE ocr_tasks SET storage_key = '1/replaced.png' WHERE id = 101"));
        assertDatabaseRejected(() -> jdbcTemplate.update(
                "UPDATE ocr_tasks SET content_sha256 = ? WHERE id = 101", HASH_B));
        assertDatabaseRejected(() -> jdbcTemplate.update(
                "UPDATE ocr_tasks SET created_by = 2 WHERE id = 101"));
    }

    private Instant resetFixtures() {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String table : List.of(
                        "audit_events",
                        "maintenance_runs",
                        "business_command_replays",
                        "ocr_retry_commands",
                        "ocr_tasks",
                        "payments",
                        "bill_tags",
                        "bills",
                        "auth_sessions",
                        "bill_code_sequences",
                        "users",
                        "tenants")) {
                    statement.execute("TRUNCATE TABLE " + table);
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            } catch (java.sql.SQLException exception) {
                try (java.sql.Statement statement = connection.createStatement()) {
                    statement.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
                throw exception;
            }
            return null;
        });

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, public_id, name, status, created_at, updated_at)
                VALUES
                    (1, '00000000-0000-0000-0000-000000000001', 'MySQL 验证租户', 'ACTIVE', ?, ?),
                    (2, '00000000-0000-0000-0000-000000000002', '第二验证租户', 'ACTIVE', ?, ?)
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
        jdbcTemplate.update(
                """
                INSERT INTO users (
                    id, tenant_id, open_id, display_name, status, version, created_at, updated_at)
                VALUES
                    (1, 1, 'mysql-test-open-id-1', 'MySQL 测试用户', 'ACTIVE', 0, ?, ?),
                    (2, 2, 'mysql-test-open-id-2', '第二测试用户', 'ACTIVE', 0, ?, ?)
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
        return now;
    }

    private void insertBill(Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO bills (
                    id, tenant_id, code, shipper, shipper_normalized, transport_date,
                    origin, destination, amount, paid_amount, status, version,
                    created_by, updated_by, created_at, updated_at)
                VALUES (10, 1, 'VALID-001', '托运人', '托运人', '2026-08-22',
                        '上海', '北京', 1000.00, 0.00, 'UNPAID', 0, 1, 1, ?, ?)
                """,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private void insertOcrTask(long id, String publicId, Instant createdAt, Instant nextAttemptAt) {
        jdbcTemplate.update(
                """
                INSERT INTO ocr_tasks (
                    id, public_id, tenant_id, created_by, status, storage_key, original_filename,
                    content_type, size_bytes, content_sha256, idempotency_key, request_hash,
                    attempt_count, max_attempts, next_attempt_at, version, created_at, updated_at)
                VALUES (?, ?, 1, 1, 'PENDING', ?, 'fixture.png', 'image/png', 10, ?, ?, ?,
                        0, 3, ?, 0, ?, ?)
                """,
                id,
                publicId,
                "1/fixture-" + id + ".png",
                HASH_A,
                "ocr-task-" + id,
                HASH_B,
                Timestamp.from(nextAttemptAt),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }

    private void insertPayment(Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO payments (
                    id, tenant_id, bill_id, amount, method, paid_at, reference_no, note,
                    idempotency_key, request_hash, status, version, created_by, created_at)
                VALUES (20, 1, 10, 100.00, 'CASH', ?, 'REF-20', '首笔收款',
                        'payment-20', ?, 'ACTIVE', 0, 1, ?)
                """,
                Timestamp.from(now),
                HASH_A,
                Timestamp.from(now));
    }

    private static void assertConstraintViolation(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void assertDatabaseRejected(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(DataAccessException.class);
    }

    private static List<String> expectedMigrationVersions() {
                return java.util.stream.IntStream.rangeClosed(1, 22)
                .mapToObj(Integer::toString)
                .toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while holding the MySQL row lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the MySQL row lock", exception);
        }
    }
}
