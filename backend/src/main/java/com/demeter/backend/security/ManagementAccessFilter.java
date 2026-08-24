package com.demeter.backend.security;

import com.demeter.backend.config.ManagementAccessProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.util.StringUtils;

@Component
public class ManagementAccessFilter extends OncePerRequestFilter {

    private static final String INFO_PATH = "/actuator/info";
    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    private final ManagementAccessProperties properties;
    private final SecurityProblemWriter problemWriter;

    public ManagementAccessFilter(
            ManagementAccessProperties properties,
            SecurityProblemWriter problemWriter) {
        this.properties = properties;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !INFO_PATH.equals(path) && !PROMETHEUS_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String expected = properties.accessToken();
        if (!StringUtils.hasText(expected)) {
            filterChain.doFilter(request, response);
            return;
        }
        String supplied = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (supplied == null || !constantTimeEquals(expected, supplied)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"demeter-management\"");
            problemWriter.write(
                    request,
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "MANAGEMENT_UNAUTHORIZED",
                    "Management endpoint authentication is required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String bearerToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
