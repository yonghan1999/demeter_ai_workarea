package com.demeter.backend.admin.security;

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
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder().maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(15)).build();

    public AdminLoginRateLimitFilter(AdminSessionService sessions) { this.sessions = sessions; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/admin/login".equals(request.getRequestURI()) || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!sessions.enabled()) { chain.doFilter(request, response); return; }
        Bucket bucket = buckets.get(request.getRemoteAddr(), ignored -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(10).refillGreedy(10, Duration.ofMinutes(1)).build()).build());
        if (!bucket.tryConsume(1)) { response.sendError(429, "Too many login attempts"); return; }
        chain.doFilter(request, response);
    }
}
