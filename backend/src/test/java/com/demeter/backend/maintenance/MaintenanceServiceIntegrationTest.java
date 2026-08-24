package com.demeter.backend.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demeter.backend.ocr.domain.OcrDocument;
import com.demeter.backend.ocr.infrastructure.OcrStorageProperties;
import com.demeter.backend.ocr.spi.OcrDocumentStorage;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = {
        "classpath:db/testdata/cleanup.sql",
        "classpath:db/testdata/bills-basic.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class MaintenanceServiceIntegrationTest {

    @Autowired
    MaintenanceService maintenanceService;

    @Autowired
    OcrDocumentStorage storage;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    OcrStorageProperties storageProperties;

    @BeforeEach
    void clearStorage() throws Exception {
        Path root = storageProperties.root().toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            Files.createDirectories(root);
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void removesExpiredSessionsAndRetainedOcrDocumentsThroughTheMaintenanceChain() {
        Instant old = Instant.now().minus(120, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        jdbcTemplate.update(
                """
                INSERT INTO auth_sessions (id, user_id, token_hash, expires_at, revoked_at, created_at)
                VALUES (?, 1101, ?, ?, NULL, ?)
                """,
                UUID.randomUUID().toString(),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                Timestamp.from(old),
                Timestamp.from(old.minus(30, ChronoUnit.DAYS)));
        jdbcTemplate.update(
                """
                INSERT INTO auth_sessions (id, user_id, token_hash, expires_at, revoked_at, created_at)
                VALUES (?, 1101, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)),
                Timestamp.from(old),
                Timestamp.from(old));

        jdbcTemplate.update(
                """
                INSERT INTO ocr_tasks (
                    id, public_id, tenant_id, created_by, status, storage_key, original_filename,
                    content_type, size_bytes, content_sha256, idempotency_key, request_hash,
                    attempt_count, max_attempts, next_attempt_at, last_error_code,
                    last_error_message, completed_at, version, created_at, updated_at)
                VALUES (1599, '00000000-0000-0000-0000-000000001599', 1001, 1101, 'FAILED',
                    '1001/retry-retained.png', 'retry-retained.png', 'image/png', 4, ?, ?, ?,
                    1, 3, ?, 'TEST_FAILURE', 'test failure', ?, 0, ?, ?)
                """,
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "maintenance-retry-task",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                Timestamp.from(recent),
                Timestamp.from(recent),
                Timestamp.from(recent),
                Timestamp.from(recent));
        jdbcTemplate.update(
                """
                INSERT INTO ocr_retry_commands (
                    tenant_id, task_id, idempotency_key, request_hash, task_version, created_by, created_at)
                VALUES (1001, 1599, 'maintenance-retry-command', ?, 0, 1101, ?)
                """,
                "abababababababababababababababababababababababababababababababab",
                Timestamp.from(old));

        OcrDocument document = new OcrDocument(
                "retained.png",
                "image/png",
                new byte[] {1, 2, 3, 4});
        String storageKey = storage.store(1001L, document.fileName(), document);
        jdbcTemplate.update(
                """
                INSERT INTO ocr_tasks (
                    public_id, tenant_id, created_by, status, storage_key, original_filename,
                    content_type, size_bytes, content_sha256, idempotency_key, request_hash,
                    provider, result_json,
                    attempt_count, max_attempts, next_attempt_at, completed_at, version,
                    created_at, updated_at)
                VALUES (?, 1001, 1101, 'SUCCEEDED', ?, ?, 'image/png', 4, ?, ?, ?,
                        'test-provider', '{}', 1, 3, ?, ?, 0, ?, ?)
                """,
                UUID.randomUUID().toString(),
                storageKey,
                document.fileName(),
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "maintenance-ocr-001",
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                Timestamp.from(old),
                Timestamp.from(old),
                Timestamp.from(old),
                Timestamp.from(old));

        MaintenanceService.MaintenanceResult result = maintenanceService.run();

        assertThat(result.deletedSessions()).isEqualTo(2);
        assertThat(result.deletedMaintenanceRuns()).isZero();
        assertThat(result.deletedOcrRetryCommands()).isEqualTo(1);
        assertThat(result.deletedOcrDocuments()).isEqualTo(1);
        assertThat(result.deletedOcrOrphans()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_sessions", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT storage_deleted_at
                FROM ocr_tasks
                WHERE idempotency_key = 'maintenance-ocr-001'
                  AND storage_cleanup_started_at IS NOT NULL
                """,
                Timestamp.class)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ocr_retry_commands", Integer.class))
                .isZero();
        assertThatThrownBy(() -> storage.load(storageKey, document.fileName(), document.contentType()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void removesOnlyOldUnreferencedStorageObjects() throws Exception {
        OcrDocument document = new OcrDocument(
                "orphan.png",
                "image/png",
                new byte[] {1, 2, 3, 4});
        String orphanKey = storage.store(1001L, "orphan.png", document);
        String referencedKey = storage.store(1001L, "referenced.png", document);
        String recentKey = storage.store(1001L, "recent.png", document);
        Path storageRoot = storageProperties.root().toAbsolutePath().normalize();
        Instant old = Instant.now().minus(2, ChronoUnit.HOURS);
        Files.setLastModifiedTime(storageRoot.resolve(orphanKey), FileTime.from(old));
        Files.setLastModifiedTime(storageRoot.resolve(referencedKey), FileTime.from(old));

        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                INSERT INTO ocr_tasks (
                    public_id, tenant_id, created_by, status, storage_key, original_filename,
                    content_type, size_bytes, content_sha256, idempotency_key, request_hash,
                    attempt_count, max_attempts, next_attempt_at, version, created_at, updated_at)
                VALUES (?, 1001, 1101, 'PENDING', ?, 'referenced.png', 'image/png', 4, ?, ?, ?,
                        0, 3, ?, 0, ?, ?)
                """,
                UUID.randomUUID().toString(),
                referencedKey,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "maintenance-referenced-object",
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));

        MaintenanceService.MaintenanceResult result = maintenanceService.run();

        assertThat(result.deletedOcrOrphans()).isEqualTo(1);
        assertThatThrownBy(() -> storage.load(orphanKey, document.fileName(), document.contentType()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(storage.load(referencedKey, document.fileName(), document.contentType()).content())
                .containsExactly(1, 2, 3, 4);
        assertThat(storage.load(recentKey, document.fileName(), document.contentType()).content())
                .containsExactly(1, 2, 3, 4);
    }
}
