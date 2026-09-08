package com.demeter.backend.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.demeter.backend.config.AdminProperties;
import com.demeter.backend.admin.infrastructure.AdminSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminSessionServiceTest {
    @Test
    void createsAndExpiresShortLivedSession() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AdminSessionRepository repository = mock(AdminSessionRepository.class);
        when(repository.findActive(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(new com.demeter.backend.admin.infrastructure.AdminSession(
                        "hash", clock.instant().plus(Duration.ofHours(1)), clock.instant())),
                        java.util.Optional.empty());
        AdminSessionService service = new AdminSessionService(
                new AdminProperties(true, "01234567890123456789012345678901", Duration.ofHours(1)),
                repository, clock);
        String token = service.createSession();
        assertThat(service.isActive(token)).isTrue();
        service.revoke(token);
        assertThat(service.isActive(token)).isFalse();
    }

    @Test
    void doesNotVerifyWhenDisabled() {
        AdminSessionService service = new AdminSessionService(
                new AdminProperties(false, "secret", Duration.ofHours(1)), mock(AdminSessionRepository.class), Clock.systemUTC());
        assertThat(service.verifyAccessToken("secret")).isFalse();
    }
}
