package com.demeter.backend.ocr.infrastructure;

import com.demeter.backend.ocr.domain.OcrDocument;
import com.demeter.backend.common.error.DependencyUnavailableException;
import com.demeter.backend.ocr.spi.OcrDocumentStorage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(
        prefix = "demeter.ocr.storage",
        name = "filesystem-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FileSystemOcrDocumentStorage implements OcrDocumentStorage {

    private static final Pattern MANAGED_KEY = Pattern.compile(
            "[0-9]+/(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:\\.[a-z0-9]{1,9})?|\\.upload-[a-zA-Z0-9._-]+\\.tmp)",
            Pattern.CASE_INSENSITIVE);

    private final Path root;

    public FileSystemOcrDocumentStorage(OcrStorageProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
    }

    @Override
    public String store(long tenantId, String objectName, OcrDocument document) {
        String safeName = UUID.randomUUID() + extension(objectName);
        String key = tenantId + "/" + safeName;
        Path target = resolve(key);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temporary, document.content());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
            restrictPermissions(target);
            return key;
        } catch (IOException exception) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The orphaned temporary file can be removed by storage maintenance.
                }
            }
            throw unavailable("Could not store the OCR document", exception);
        }
    }

    @Override
    public OcrDocument load(String storageKey, String originalFilename, String contentType) {
        try {
            return new OcrDocument(originalFilename, contentType, Files.readAllBytes(resolve(storageKey)));
        } catch (IOException exception) {
            throw unavailable("Could not load the OCR document", exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw unavailable("Could not delete the OCR document", exception);
        }
    }

    @Override
    public boolean supportsListing() {
        return true;
    }

    @Override
    public List<com.demeter.backend.ocr.spi.OcrStorageObject> listObjectsOlderThan(
            Instant olderThan,
            int limit) {
        Objects.requireNonNull(olderThan, "olderThan");
        if (limit < 1) {
            throw new IllegalArgumentException("OCR storage listing limit must be positive");
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> objectIfOlderThan(path, olderThan))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(com.demeter.backend.ocr.spi.OcrStorageObject::lastModified)
                            .thenComparing(com.demeter.backend.ocr.spi.OcrStorageObject::key))
                    .limit(limit)
                    .toList();
        } catch (IOException exception) {
            throw unavailable("Could not enumerate OCR document storage", exception);
        }
    }

    @Override
    public void verifyAvailability() {
        Path probe = null;
        try {
            Files.createDirectories(root);
            probe = Files.createTempFile(root, ".health-", ".tmp");
        } catch (IOException exception) {
            throw unavailable("OCR document storage is not writable", exception);
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException exception) {
                    throw unavailable(
                            "OCR document storage health probe could not be removed", exception);
                }
            }
        }
    }

    private static DependencyUnavailableException unavailable(String message, IOException cause) {
        return new DependencyUnavailableException("OCR_STORAGE_UNAVAILABLE", message, cause);
    }

    private Path resolve(String key) {
        if (key == null || !MANAGED_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid OCR storage key");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid OCR storage key");
        }
        Path current = root;
        for (Path part : root.relativize(resolved)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Invalid OCR storage key");
            }
        }
        return resolved;
    }

    private com.demeter.backend.ocr.spi.OcrStorageObject objectIfOlderThan(
            Path path,
            Instant olderThan) {
        try {
            String key = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
            if (!MANAGED_KEY.matcher(key).matches()) {
                return null;
            }
            FileTime modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
            Instant lastModified = modified.toInstant();
            if (!lastModified.isBefore(olderThan)) {
                return null;
            }
            return new com.demeter.backend.ocr.spi.OcrStorageObject(key, lastModified, Files.size(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect an OCR storage object", exception);
        }
    }

    private static void restrictPermissions(Path target) throws IOException {
        if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(target, permissions);
        }
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || fileName.length() - dot > 10) {
            return "";
        }
        String extension = fileName.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return extension.matches("\\.[a-z0-9]+") ? extension : "";
    }
}
