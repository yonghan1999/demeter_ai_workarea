package com.demeter.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class NoStoreApiResponseFilterTest {

    private final NoStoreApiResponseFilter filter = new NoStoreApiResponseFilter();

    @Test
    void marksApiResponsesAsNotCacheable() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/bills"), response, (request, servletResponse) -> {
        });

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
    }

    @Test
    void leavesNonApiResponsesUntouched() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), response, (request, servletResponse) -> {
        });

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isNull();
    }
}
