package com.demeter.backend.admin.security;

import com.demeter.backend.admin.infrastructure.AdminSession;
import com.demeter.backend.admin.infrastructure.AdminSessionRepository;
import com.demeter.backend.config.AdminProperties;
import com.demeter.backend.security.TokenDigests;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSessionService {
    public static final String COOKIE_NAME = "DEMETER_ADMIN_SESSION";
    private final AdminProperties properties;
    private final AdminSessionRepository repository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AdminSessionService(AdminProperties properties, AdminSessionRepository repository, Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.enabled() && properties.accessToken() != null && !properties.accessToken().isBlank();
    }

    public long sessionTtlSeconds() {
        return properties.sessionTtl().toSeconds();
    }

    public boolean verifyAccessToken(String supplied) {
        return enabled() && supplied != null && java.security.MessageDigest.isEqual(
                properties.accessToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Transactional
    public String createSession() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        repository.save(new AdminSession(TokenDigests.sha256(token), now.plus(properties.sessionTtl()), now));
        return token;
    }

    @Transactional(readOnly = true)
    public boolean isActive(String token) {
        return token != null && !token.isBlank()
                && repository.findActive(TokenDigests.sha256(token), clock.instant()).isPresent();
    }

    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        repository.findByTokenHash(TokenDigests.sha256(token)).ifPresent(session -> session.revoke(clock.instant()));
    }
}
