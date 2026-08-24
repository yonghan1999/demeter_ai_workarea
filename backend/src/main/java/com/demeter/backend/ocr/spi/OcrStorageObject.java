package com.demeter.backend.ocr.spi;

import java.time.Instant;
import java.util.Objects;

/** A bounded, provider-neutral description of an object eligible for storage maintenance. */
public record OcrStorageObject(String key, Instant lastModified, long sizeBytes) {

    public OcrStorageObject {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("OCR storage object key must not be blank");
        }
        Objects.requireNonNull(lastModified, "lastModified");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("OCR storage object size must not be negative");
        }
    }
}
