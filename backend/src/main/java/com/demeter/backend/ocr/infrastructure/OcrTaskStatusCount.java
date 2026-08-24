package com.demeter.backend.ocr.infrastructure;

import com.demeter.backend.ocr.domain.OcrTaskStatus;

public record OcrTaskStatusCount(OcrTaskStatus status, long taskCount) {
}
