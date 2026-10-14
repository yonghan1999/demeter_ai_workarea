package com.demeter.backend.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.demeter.backend.auth.spi.WechatCodeExchangeClient;
import com.demeter.backend.auth.spi.WechatIdentity;
import com.demeter.backend.security.DemeterPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import java.nio.charset.StandardCharsets;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
    void savesNicknameWithoutReplacingTheSessionAndPreservesItOnSilentLogin() throws Exception {
        JsonNode login = login("valid-wechat-code");
        String token = login.get("accessToken").asText();
        long userId = login.get("user").get("id").asLong();
        JsonNode other = login("other-wechat-code");
        mockMvc.perform(put("/api/v1/users/me/nickname")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("displayName", "  用户选择的昵称  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is((int) userId)))
                .andExpect(jsonPath("$.tenantId", is(login.get("user").get("tenantId").asText())))
                .andExpect(jsonPath("$.displayName", is("用户选择的昵称")));
        mockMvc.perform(put("/api/v1/users/me/nickname")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("displayName", "用户选择的昵称"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName", is("用户选择的昵称")));
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName", is("微信用户")));
        assertThat(login("valid-wechat-code").get("user").get("displayName").asText())
                .isEqualTo("用户选择的昵称");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'USER_NICKNAME_UPDATED' AND actor_user_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void rejectsUnauthenticatedAndInvalidNicknameUpdates() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/users/me/nickname")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        JsonNode login = login("valid-wechat-code");
        for (String name : List.of("", "   ", "a".repeat(121))) {
            mockMvc.perform(put("/api/v1/users/me/nickname")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.get("accessToken").asText())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("displayName", name))))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM users", String.class)).isEqualTo("微信用户");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'USER_NICKNAME_UPDATED'", Integer.class)).isZero();
    }

    @Test
    void rejectsAProfileUpdateWhenThePrincipalHasTheWrongTenant() throws Exception {
        JsonNode login = login("valid-wechat-code");
        long id = login.get("user").get("id").asLong();
        long tenantId = jdbcTemplate.queryForObject("SELECT tenant_id FROM users WHERE id = ?", Long.class, id);
        var principal = new DemeterPrincipal(id, tenantId + 1, "test-open-id", "微信用户");
        var tokenAuthentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        mockMvc.perform(put("/api/v1/users/me/nickname")
                        .with(authentication(tokenAuthentication))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"跨租户昵称\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM users", String.class)).isEqualTo("微信用户");
    }

    private JsonNode login(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
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
                if (!"valid-wechat-code".equals(code) && !"other-wechat-code".equals(code)) {
                    throw new IllegalArgumentException("Unexpected test code");
                }
                return new WechatIdentity(
                        "other-wechat-code".equals(code) ? "other-wechat-open-id" : "test-wechat-open-id",
                        "other-wechat-code".equals(code) ? null : "test-wechat-union-id");
            };
        }
    }
}
