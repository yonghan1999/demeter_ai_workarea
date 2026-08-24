package com.demeter.backend.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.demeter.backend.auth.spi.WechatCodeExchangeClient;
import com.demeter.backend.auth.spi.WechatIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "demeter.auth.max-active-sessions=2")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthControllerIntegrationTest.FakeWechatConfiguration.class)
@Sql(scripts = "classpath:db/testdata/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AuthControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void createsAUserSessionAuthenticatesRequestsAndRevokesTheSession() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "valid-wechat-code",
                                "displayName", "  货运老板  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.user.displayName", is("货运老板")))
                .andReturn();

        JsonNode response = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = response.get("accessToken").asText();
        assertThat(token).hasSizeGreaterThanOrEqualTo(40);

        Map<String, Object> session = jdbcTemplate.queryForMap(
                "SELECT token_hash, revoked_at FROM auth_sessions");
        assertThat(session.get("token_hash")).isNotEqualTo(token);
        assertThat(session.get("token_hash").toString()).hasSize(64);
        assertThat(session.get("revoked_at")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenants", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bill_code_sequences", Integer.class)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/bills")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok", is(true)));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok", is(true)));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM auth_sessions", java.sql.Timestamp.class)).isNotNull();

        mockMvc.perform(get("/api/v1/bills")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reusesTheExistingUserAndCreatesASeparateSession() throws Exception {
        String request = objectMapper.writeValueAsString(Map.of(
                "code", "valid-wechat-code",
                "displayName", "货运老板"));

        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenants", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_sessions", Integer.class)).isEqualTo(2);
    }

    @Test
    void serializesConcurrentFirstLoginsAndEnforcesTheActiveSessionLimit() throws Exception {
        int requests = 4;
        var executor = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        String request = objectMapper.writeValueAsString(Map.of(
                "code", "valid-wechat-code",
                "displayName", "并发登录用户"));
        try {
            java.util.List<Future<Integer>> responses = new java.util.ArrayList<>();
            for (int index = 0; index < requests; index++) {
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent login start timed out");
                    }
                    return mockMvc.perform(post("/api/v1/auth/wechat/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(request))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> response : responses) {
                assertThat(response.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenants", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE revoked_at IS NULL",
                Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_sessions", Integer.class))
                .isEqualTo(requests);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeWechatConfiguration {

        @Bean
        @Primary
        WechatCodeExchangeClient fakeWechatCodeExchangeClient() {
            return code -> {
                if (!"valid-wechat-code".equals(code)) {
                    throw new IllegalArgumentException("Unexpected test code");
                }
                return new WechatIdentity("test-wechat-open-id", "test-wechat-union-id");
            };
        }
    }
}
