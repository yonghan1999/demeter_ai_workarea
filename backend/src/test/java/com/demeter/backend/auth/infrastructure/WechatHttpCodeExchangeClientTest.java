package com.demeter.backend.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demeter.backend.common.error.ExternalServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.time.Duration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
                RestClient.builder(),
                new ObjectMapper());

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

    @Test
    void parsesJsonWhenWechatReturnsTextPlainContentType() throws IOException {
        try (LocalWechatServer server = new LocalWechatServer(
                "text/plain",
                "{\"openid\":\"openid-test\",\"session_key\":\"session-key-test\"}")) {
            WechatHttpCodeExchangeClient client = new WechatHttpCodeExchangeClient(
                    new WechatProperties(
                            "wx-test-app",
                            "test-secret",
                            server.url(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1)),
                    RestClient.builder(),
                    new ObjectMapper());

            assertThat(client.exchange("temporary-login-code"))
                    .isEqualTo(new com.demeter.backend.auth.spi.WechatIdentity("openid-test", null));
        }
    }

    @Test
    void classifiesMalformedWechatJsonAsInvalidResponse() throws IOException {
        try (LocalWechatServer server = new LocalWechatServer("text/plain", "not-json")) {
            WechatHttpCodeExchangeClient client = new WechatHttpCodeExchangeClient(
                    new WechatProperties(
                            "wx-test-app",
                            "test-secret",
                            server.url(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1)),
                    RestClient.builder(),
                    new ObjectMapper());

            assertThatThrownBy(() -> client.exchange("temporary-login-code"))
                    .isInstanceOf(ExternalServiceException.class)
                    .extracting(exception -> ((ExternalServiceException) exception).getCode())
                    .isEqualTo("WECHAT_INVALID_RESPONSE");
        }
    }

    private static final class LocalWechatServer implements AutoCloseable {

        private final HttpServer server;

        private LocalWechatServer(String contentType, String responseBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/sns/jscode2session", exchange -> respond(exchange, contentType, responseBody));
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/sns/jscode2session";
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void respond(HttpExchange exchange, String contentType, String responseBody)
                throws IOException {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        }
    }
}
