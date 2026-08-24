package com.demeter.backend.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demeter.backend.common.error.ExternalServiceException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class WechatHttpCodeExchangeClientTest {

    @Test
    void doesNotExposeWechatSecretsWhenTheHttpExchangeFails() {
        WechatHttpCodeExchangeClient client = new WechatHttpCodeExchangeClient(
                new WechatProperties(
                        "wx-production-app",
                        "super-sensitive-wechat-secret",
                        "http://127.0.0.1:1/sns/jscode2session",
                        Duration.ofMillis(100),
                        Duration.ofMillis(100)),
                RestClient.builder());

        assertThatThrownBy(() -> client.exchange("temporary-login-code"))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessage("WeChat login service is temporarily unavailable")
                .hasNoCause()
                .satisfies(exception -> {
                    String rendered = exception.toString();
                    org.assertj.core.api.Assertions.assertThat(rendered)
                            .doesNotContain("super-sensitive-wechat-secret")
                            .doesNotContain("temporary-login-code")
                            .doesNotContain("jscode2session");
                });
    }
}
