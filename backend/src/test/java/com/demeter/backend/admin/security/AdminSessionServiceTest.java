package com.demeter.backend.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.demeter.backend.config.AdminProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AdminSessionServiceTest {
    @Test
    void createsAndExpiresShortLivedSession() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AdminSessionService service = new AdminSessionService(
                new AdminProperties(true, "01234567890123456789012345678901", Duration.ofHours(1)), clock);
        String token = service.createSession();
        assertThat(service.isActive(token)).isTrue();
        service.revoke(token);
        assertThat(service.isActive(token)).isFalse();
    }

    @Test
    void doesNotVerifyWhenDisabled() {
        AdminSessionService service = new AdminSessionService(
                new AdminProperties(false, "secret", Duration.ofHours(1)), Clock.systemUTC());
        assertThat(service.verifyAccessToken("secret")).isFalse();
    }
}
