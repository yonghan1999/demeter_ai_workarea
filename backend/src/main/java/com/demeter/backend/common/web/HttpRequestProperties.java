package com.demeter.backend.common.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demeter.http")
public record HttpRequestProperties(
        @Min(1024) @Max(104_857_600) long maxBodyBytes) {
}
