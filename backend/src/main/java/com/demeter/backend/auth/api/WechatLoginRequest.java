package com.demeter.backend.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WechatLoginRequest(
        @NotBlank @Size(max = 256) String code,
        @Size(max = 120) String displayName) {
}
