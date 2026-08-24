package com.demeter.backend.ocr.domain;

import java.util.List;

public record OcrRecognitionResult(
        String provider,
        String providerRequestId,
        List<OcrBillCandidate> bills) {
}
