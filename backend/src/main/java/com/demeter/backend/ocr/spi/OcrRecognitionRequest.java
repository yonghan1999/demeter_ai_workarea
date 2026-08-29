package com.demeter.backend.ocr.spi;

import com.demeter.backend.ocr.domain.OcrDocument;
import java.util.Objects;

public record OcrRecognitionRequest(
        String taskId,
        int attempt,
        OcrDocument document,
        OcrRecognitionMemory memory) {

    public OcrRecognitionRequest(String taskId, int attempt, OcrDocument document) {
        this(taskId, attempt, document, OcrRecognitionMemory.empty());
    }

    public OcrRecognitionRequest {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("OCR task id must not be blank");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("OCR attempt must be positive");
        }
        Objects.requireNonNull(document, "document");
        memory = memory == null ? OcrRecognitionMemory.empty() : memory;
    }
}
