package com.demeter.backend.ocr.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum OcrTaskStatus {
    PENDING("pending"),
    PROCESSING("processing"),
    RETRYING("retrying"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    private final String value;

    OcrTaskStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
