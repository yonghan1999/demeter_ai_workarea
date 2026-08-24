package com.demeter.backend.auth.api;

import java.time.Instant;

public record LoginResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        UserView user) {

    public record UserView(
            long id,
            String tenantId,
            String displayName) {
    }
}
