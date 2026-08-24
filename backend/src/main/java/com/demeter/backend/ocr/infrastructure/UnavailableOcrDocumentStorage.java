package com.demeter.backend.ocr.infrastructure;

import com.demeter.backend.common.error.DependencyUnavailableException;
import com.demeter.backend.ocr.domain.OcrDocument;
import com.demeter.backend.ocr.spi.OcrDocumentStorage;
import com.demeter.backend.ocr.spi.OcrStorageObject;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Keeps API processes bootable when OCR storage is intentionally disabled or not yet configured.
 * Worker readiness remains down until a real storage adapter is installed.
 */
@Component
@ConditionalOnMissingBean(OcrDocumentStorage.class)
public class UnavailableOcrDocumentStorage implements OcrDocumentStorage {

    private static final String CODE = "OCR_STORAGE_UNAVAILABLE";

    @Override
    public String store(long tenantId, String objectName, OcrDocument document) {
        throw unavailable();
    }

    @Override
    public OcrDocument load(String storageKey, String originalFilename, String contentType) {
        throw unavailable();
    }

    @Override
    public void delete(String storageKey) {
        throw unavailable();
    }

    @Override
    public void verifyAvailability() {
        throw unavailable();
    }

    @Override
    public List<OcrStorageObject> listObjectsOlderThan(Instant olderThan, int limit) {
        throw unavailable();
    }

    private static DependencyUnavailableException unavailable() {
        return new DependencyUnavailableException(
                CODE,
                "OCR document storage is not configured or temporarily unavailable");
    }
}
