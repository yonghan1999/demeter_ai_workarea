package com.demeter.backend.ocr.infrastructure;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.ocr.qwen")
public record QwenOcrProperties(
        boolean enabled,
        String apiKey,
        URI chatCompletionsUrl,
        String model,
        int maxOutputTokens) {

    public QwenOcrProperties {
        if (enabled && !StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("Qwen OCR requires an API key when enabled");
        }
        if (chatCompletionsUrl == null || !"https".equalsIgnoreCase(chatCompletionsUrl.getScheme())) {
            throw new IllegalArgumentException("Qwen OCR endpoint must use HTTPS");
        }
        if (!StringUtils.hasText(model)) {
            throw new IllegalArgumentException("Qwen OCR model must not be blank");
        }
        if (maxOutputTokens < 256 || maxOutputTokens > 8_192) {
            throw new IllegalArgumentException("Qwen OCR maximum output tokens must be between 256 and 8192");
        }
    }
}
