package com.demeter.backend.ocr.spi;

import com.demeter.backend.ocr.domain.OcrDocument;
import java.time.Instant;
import java.util.List;

public interface OcrDocumentStorage {

    String store(long tenantId, String objectName, OcrDocument document);

    OcrDocument load(String storageKey, String originalFilename, String contentType);

    void delete(String storageKey);

    /**
     * Whether this adapter can enumerate objects for orphan cleanup. Remote adapters should only
     * return true when listing is bounded and scoped to the application's bucket/prefix.
     */
    default boolean supportsListing() {
        return false;
    }

    /**
     * Lists at most {@code limit} objects older than {@code olderThan}. The default keeps adapters
     * that do not support listing safe; their lifecycle cleanup still uses database-owned keys.
     */
    default List<OcrStorageObject> listObjectsOlderThan(Instant olderThan, int limit) {
        return List.of();
    }

    default void verifyAvailability() {
    }
}
