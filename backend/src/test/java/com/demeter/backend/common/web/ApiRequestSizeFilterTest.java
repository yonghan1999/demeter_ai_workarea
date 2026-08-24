package com.demeter.backend.common.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demeter.backend.security.SecurityProblemWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiRequestSizeFilterTest {

    @Test
    void limitsBodiesWhenTheClientDoesNotSendContentLength() {
        ApiRequestSizeFilter filter = new ApiRequestSizeFilter(
                new HttpRequestProperties(1024),
                new SecurityProblemWriter(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/wechat/login") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent("x".repeat(2048).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> filter.doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (wrappedRequest, response) -> wrappedRequest.getInputStream().readAllBytes()))
                .isInstanceOf(ApiRequestSizeFilter.RequestBodyTooLargeException.class);
    }
}
