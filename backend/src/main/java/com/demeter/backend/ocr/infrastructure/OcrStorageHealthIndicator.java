package com.demeter.backend.ocr.infrastructure;

import com.demeter.backend.ocr.spi.OcrDocumentStorage;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("ocrStorageHealthIndicator")
@ConditionalOnRuntimeRole({RuntimeRole.WORKER, RuntimeRole.MAINTENANCE})
public class OcrStorageHealthIndicator implements HealthIndicator {

    private final OcrDocumentStorage storage;

    public OcrStorageHealthIndicator(OcrDocumentStorage storage) {
        this.storage = storage;
    }

    @Override
    public Health health() {
        try {
            storage.verifyAvailability();
            return Health.up().build();
        } catch (RuntimeException exception) {
            return Health.down(exception).build();
        }
    }
}
