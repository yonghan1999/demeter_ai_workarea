package com.demeter.backend.config;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demeter.business-time")
public record BusinessTimeProperties(String zoneId) {

    public BusinessTimeProperties {
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("Business time zone must be configured");
        }
        try {
            ZoneId.of(zoneId.trim());
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Business time zone is invalid: " + zoneId, exception);
        }
        zoneId = zoneId.trim();
    }

    public ZoneId zone() {
        return ZoneId.of(zoneId);
    }
}
