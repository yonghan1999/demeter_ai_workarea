package com.demeter.backend.ocr.domain;

import java.util.Arrays;

public record OcrDocument(String fileName, String contentType, byte[] content) {

    public OcrDocument {
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
