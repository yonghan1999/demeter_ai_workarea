package com.demeter.backend.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateNicknameRequest(@NotBlank @Size(max = 120) String displayName) {
}
