package com.demeter.backend.ocr.infrastructure;

import java.nio.file.Path;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.ocr.storage")
public record OcrStorageProperties(@NotNull Path root, boolean shared) {

    public OcrStorageProperties {
        if (root != null && !root.toString().isBlank()) {
            root = root.normalize();
        }
    }
}
