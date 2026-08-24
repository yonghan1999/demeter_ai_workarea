package com.demeter.backend.ocr.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.demeter.backend.ocr.domain.OcrDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemOcrDocumentStorageTest {

    @TempDir
    Path root;

    @Test
    void storesDocumentsWithOwnerOnlyPermissionsOnPosixFileSystems() throws Exception {
        FileSystemOcrDocumentStorage storage = new FileSystemOcrDocumentStorage(
                new OcrStorageProperties(root, false));

        String key = storage.store(
                42,
                "ledger.jpg",
                new OcrDocument("ledger.jpg", "image/jpeg", new byte[] {1, 2, 3}));
        Path stored = root.resolve(key);

        assertThat(Files.readAllBytes(stored)).containsExactly(1, 2, 3);
        if (stored.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(stored)))
                    .isEqualTo("rw-------");
        }
    }

    @Test
    void listsOnlyOldRegularObjectsWithNormalizedStorageKeys() throws Exception {
        FileSystemOcrDocumentStorage storage = new FileSystemOcrDocumentStorage(
                new OcrStorageProperties(root, false));
        String oldKey = storage.store(
                42,
                "old-ledger.jpg",
                new OcrDocument("old-ledger.jpg", "image/jpeg", new byte[] {1, 2, 3}));
        String recentKey = storage.store(
                42,
                "recent-ledger.jpg",
                new OcrDocument("recent-ledger.jpg", "image/jpeg", new byte[] {4, 5, 6}));
        Instant old = Instant.now().minusSeconds(7200);
        Files.setLastModifiedTime(root.resolve(oldKey), FileTime.from(old));

        var objects = storage.listObjectsOlderThan(Instant.now().minusSeconds(3600), 10);

        assertThat(objects).extracting(com.demeter.backend.ocr.spi.OcrStorageObject::key)
                .containsExactly(oldKey)
                .doesNotContain(recentKey);
        assertThat(objects.get(0).lastModified()).isEqualTo(old);
        assertThat(objects.get(0).sizeBytes()).isEqualTo(3);
    }

    @Test
    void rejectsTraversalKeysForLoadsAndDeletes() {
        FileSystemOcrDocumentStorage storage = new FileSystemOcrDocumentStorage(
                new OcrStorageProperties(root, false));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                        storage.load("../outside.jpg", "outside.jpg", "image/jpeg")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> storage.delete("../outside.jpg")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ignoresUnmanagedFilesDuringOrphanEnumeration() throws Exception {
        FileSystemOcrDocumentStorage storage = new FileSystemOcrDocumentStorage(
                new OcrStorageProperties(root, false));
        Path unrelated = root.resolve("shared-report.csv");
        Files.writeString(unrelated, "must remain");
        Files.setLastModifiedTime(unrelated, FileTime.from(Instant.now().minusSeconds(7200)));

        assertThat(storage.listObjectsOlderThan(Instant.now().minusSeconds(3600), 10)).isEmpty();
        assertThat(Files.exists(unrelated)).isTrue();
    }
}
