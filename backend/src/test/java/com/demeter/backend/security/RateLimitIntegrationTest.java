package com.demeter.backend.security;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.demeter.backend.auth.spi.WechatCodeExchangeClient;
import com.demeter.backend.auth.spi.WechatIdentity;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "demeter.rate-limit.enabled=true",
        "demeter.rate-limit.login.capacity=1",
        "demeter.rate-limit.login.refill-tokens=1",
        "demeter.rate-limit.login.refill-period=1h"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RateLimitIntegrationTest.FakeWechatConfiguration.class)
@Sql(scripts = "classpath:db/testdata/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class RateLimitIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void rejectsLoginBurstsWithRetryAfterAndAProblemResponse() throws Exception {
        String request = objectMapper.writeValueAsString(Map.of(
                "code", "rate-limit-code",
                "displayName", "限流测试"));

        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .with(servletRequest -> {
                            servletRequest.setRemoteAddr("192.0.2.10");
                            return servletRequest;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .with(servletRequest -> {
                            servletRequest.setRemoteAddr("192.0.2.10");
                            return servletRequest;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.code", is("RATE_LIMIT_EXCEEDED")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeWechatConfiguration {

        @Bean
        @Primary
        WechatCodeExchangeClient fakeWechatCodeExchangeClient() {
            return code -> new WechatIdentity("rate-limit-open-id", null);
        }
    }
}
