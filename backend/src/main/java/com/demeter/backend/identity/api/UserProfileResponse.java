package com.demeter.backend.identity.api;

public record UserProfileResponse(long id, String tenantId, String displayName) {
}
