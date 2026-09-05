package com.demeter.backend.admin.security;

import com.demeter.backend.config.AdminProperties;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class AdminSessionService {

    public static final String COOKIE_NAME = "DEMETER_ADMIN_SESSION";
    private final AdminProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();

    public AdminSessionService(AdminProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.enabled() && properties.accessToken() != null && !properties.accessToken().isBlank();
    }

    public long sessionTtlSeconds() {
        return properties.sessionTtl().toSeconds();
    }

    public boolean verifyAccessToken(String supplied) {
        return enabled() && supplied != null && MessageDigest.isEqual(
                properties.accessToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public int activeSessionCount() {
        sessions.entrySet().removeIf(entry -> !entry.getValue().isAfter(clock.instant()));
        return sessions.size();
    }

    public String createSession() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, clock.instant().plus(properties.sessionTtl()));
        return token;
    }

    public boolean isActive(String token) {
        if (token == null) return false;
        Instant expiry = sessions.get(token);
        if (expiry == null) return false;
        if (!expiry.isAfter(clock.instant())) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    public void revoke(String token) {
        if (token != null) sessions.remove(token);
    }
}
