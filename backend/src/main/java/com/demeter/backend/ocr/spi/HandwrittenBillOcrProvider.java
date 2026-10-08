package com.demeter.backend.ocr.spi;

import com.demeter.backend.ocr.domain.OcrRecognitionResult;

/**
 * Provider boundary for handwritten freight-bill recognition.
 * The optional Qwen adapter implements this boundary when enabled.
 */
public interface HandwrittenBillOcrProvider {

    /**
     * Recognizes one queued document. Providers should use {@code request.taskId()}
     * as their idempotency or client request token when the upstream API supports it.
     */
    OcrRecognitionResult recognize(OcrRecognitionRequest request);
}
