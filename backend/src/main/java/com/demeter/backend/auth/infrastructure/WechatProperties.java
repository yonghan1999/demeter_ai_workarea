package com.demeter.backend.auth.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demeter.wechat")
public record WechatProperties(
        String appId,
        String appSecret,
        String codeToSessionUrl,
        Duration connectTimeout,
        Duration readTimeout) {
}
