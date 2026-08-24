package com.demeter.backend.ocr.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.ocr.result")
public record OcrResultProperties(
        @Min(1) @Max(1000) int maxBills,
        @Min(1024) @Max(16_777_216) int maxJsonBytes) {
}
