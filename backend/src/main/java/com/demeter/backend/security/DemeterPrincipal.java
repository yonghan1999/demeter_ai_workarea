package com.demeter.backend.security;

public record DemeterPrincipal(
        long userId,
        long tenantId,
        String openId,
        String displayName) {
}
