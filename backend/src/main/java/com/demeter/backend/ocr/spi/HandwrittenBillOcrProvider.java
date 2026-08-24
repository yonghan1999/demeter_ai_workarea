package com.demeter.backend.ocr.spi;

import com.demeter.backend.ocr.domain.OcrRecognitionResult;

/**
 * Provider boundary for handwritten freight-bill recognition.
 * The Alibaba Cloud adapter will implement this interface in a later phase.
 */
public interface HandwrittenBillOcrProvider {

    /**
     * Recognizes one queued document. Providers should use {@code request.taskId()}
     * as their idempotency or client request token when the upstream API supports it.
     */
    OcrRecognitionResult recognize(OcrRecognitionRequest request);
}
