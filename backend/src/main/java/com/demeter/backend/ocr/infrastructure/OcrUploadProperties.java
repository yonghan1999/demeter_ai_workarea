package com.demeter.backend.ocr.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.ocr.upload")
public record OcrUploadProperties(
        @Min(1024) @Max(104_857_600) long maxBytes,
        @Min(64) @Max(100_000) int maxWidth,
        @Min(64) @Max(100_000) int maxHeight,
        @Min(4096) @Max(1_000_000_000) long maxPixels) {

    public OcrUploadProperties {
        if (maxWidth > 0 && maxHeight > 0 && maxPixels > 0
                && (long) maxWidth * maxHeight < maxPixels) {
            throw new IllegalArgumentException("OCR maximum pixels must not exceed the maximum dimensions");
        }
    }
}
