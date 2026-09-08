package com.demeter.backend.security;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "demeter.management.access-token=0123456789abcdef0123456789abcdef",
        "demeter.admin.enabled=true",
        "demeter.admin.access-token=0123456789abcdef0123456789abcdef",
        "demeter.http.max-body-bytes=1024"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHardeningIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void protectsOperationalEndpointsWithASeparateBearerToken() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"demeter-management\""))
                .andExpect(jsonPath("$.code", is("MANAGEMENT_UNAUTHORIZED")));

        mockMvc.perform(get("/actuator/info")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer 0123456789abcdef0123456789abcdef"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAnOversizedApiRequestBeforeBusinessProcessing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + "x".repeat(2048) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code", is("PAYLOAD_TOO_LARGE")))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void addsRestrictiveBrowserSecurityHeadersToApiResponses() throws Exception {
        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=()"));
    }

    @Test
    void allowsOnlySameOriginAssetsForAdminPagesWithoutRelaxingApiPolicy() throws Exception {
        mockMvc.perform(get("/admin/admin.css"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Security-Policy",
                        "default-src 'none'; style-src 'self'; script-src 'self'; frame-ancestors 'none'"));

        mockMvc.perform(get("/admin/admin.js"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Security-Policy",
                        "default-src 'none'; style-src 'self'; script-src 'self'; frame-ancestors 'none'"));
    }

    @Test
    void protectsAdminRoutesWhenApplicationUsesAContextPath() throws Exception {
        mockMvc.perform(get("/demeter/admin/tenants").contextPath("/demeter").servletPath("/admin/tenants"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/demeter/admin/login"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        "default-src 'none'; style-src 'self'; script-src 'self'; frame-ancestors 'none'"));
    }

    @Test
    void doesNotEnableBrowserCorsForTheMiniProgramApi() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(
                                "/api/v1/auth/wechat/login")
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
