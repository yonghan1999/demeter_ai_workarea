package com.demeter.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demeter.management")
public record ManagementAccessProperties(String accessToken) {
}
