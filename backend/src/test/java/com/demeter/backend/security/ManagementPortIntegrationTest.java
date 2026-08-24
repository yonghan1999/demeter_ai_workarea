package com.demeter.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "management.endpoint.health.group.readiness.include=readinessState,db,databaseSchema,roleReadiness",
                "management.endpoint.health.group.liveness.include=livenessState",
                "management.endpoint.health.show-details=always",
                "management.prometheus.metrics.export.enabled=true",
                "demeter.management.access-token=0123456789abcdef0123456789abcdef"
        })
@ActiveProfiles("test")
class ManagementPortIntegrationTest {

    private static final String MANAGEMENT_TOKEN = "0123456789abcdef0123456789abcdef";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    int applicationPort;

    @LocalManagementPort
    int managementPort;

    @Test
    void exposesHealthOnlyOnTheManagementPortAndProtectsOperationalEndpoints()
            throws IOException, InterruptedException {
        HttpResponse<String> anonymousInfo = get(managementPort, "/actuator/info", null);
        assertThat(anonymousInfo.statusCode()).isEqualTo(401);
        assertThat(anonymousInfo.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE))
                .contains("Bearer realm=\"demeter-management\"");

        HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness", null);
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"databaseSchema\"");
        assertThat(readiness.body()).contains("\"roleReadiness\"");

        assertThat(get(managementPort, "/actuator/info", MANAGEMENT_TOKEN).statusCode())
                .isEqualTo(200);
        assertThat(get(managementPort, "/actuator/prometheus", MANAGEMENT_TOKEN).statusCode())
                .isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health", null).statusCode())
                .isEqualTo(200);

        assertThat(get(applicationPort, "/actuator/health", null).statusCode())
                .isEqualTo(404);
    }

    private HttpResponse<String> get(int port, String path, String bearerToken)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (bearerToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
