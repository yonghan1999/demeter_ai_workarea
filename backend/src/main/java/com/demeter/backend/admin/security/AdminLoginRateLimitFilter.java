package com.demeter.backend.admin.security;

import static com.demeter.backend.common.web.RequestPaths.applicationPath;

import com.demeter.backend.security.RateLimitProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminLoginRateLimitFilter extends OncePerRequestFilter {
    private final AdminSessionService sessions;
    private final RateLimitProperties.Policy loginPolicy;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder().maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(15)).build();

    public AdminLoginRateLimitFilter(AdminSessionService sessions, RateLimitProperties rateLimitProperties) {
        this.sessions = sessions;
        this.loginPolicy = rateLimitProperties.login();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/admin/login".equals(applicationPath(request)) || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!sessions.enabled()) { chain.doFilter(request, response); return; }
        Bucket bucket = buckets.get(request.getRemoteAddr(), ignored -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(loginPolicy.capacity())
                        .refillGreedy(loginPolicy.refillTokens(), loginPolicy.refillPeriod())
                        .build())
                .build());
        if (!bucket.tryConsume(1)) { response.sendError(429, "Too many login attempts"); return; }
        chain.doFilter(request, response);
    }

}
