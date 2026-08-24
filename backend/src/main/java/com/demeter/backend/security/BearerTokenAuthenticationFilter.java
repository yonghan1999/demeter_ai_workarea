package com.demeter.backend.security;

import com.demeter.backend.identity.infrastructure.AuthSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final int MAX_TOKEN_LENGTH = 512;

    private final AuthSessionRepository sessionRepository;
    private final Clock clock;

    public BearerTokenAuthenticationFilter(
            AuthSessionRepository sessionRepository,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token != null) {
            authenticate(token);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(String token) {
        Instant now = clock.instant();
        sessionRepository.findAuthenticatedPrincipal(TokenDigests.sha256(token), now)
                .ifPresent(principal -> {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
                });
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return null;
        }
        return token;
    }
}
