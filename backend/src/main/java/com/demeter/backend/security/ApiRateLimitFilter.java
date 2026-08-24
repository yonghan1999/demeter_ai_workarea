package com.demeter.backend.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v1/auth/wechat/login";
    private static final String OCR_TASKS_PATH = "/api/v1/ocr/tasks";

    private final RateLimitProperties properties;
    private final SecurityProblemWriter problemWriter;
    private final Cache<String, Bucket> buckets;
    private final Counter loginRejections;
    private final Counter authenticatedRejections;
    private final Counter ocrUploadRejections;
    private final Counter ocrRetryRejections;

    public ApiRateLimitFilter(
            RateLimitProperties properties,
            SecurityProblemWriter problemWriter,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.problemWriter = problemWriter;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(properties.maximumKeys())
                .expireAfterAccess(properties.expireAfterAccess())
                .build();
        this.loginRejections = rejectionCounter(meterRegistry, "login");
        this.authenticatedRejections = rejectionCounter(meterRegistry, "authenticated");
        this.ocrUploadRejections = rejectionCounter(meterRegistry, "ocr-upload");
        this.ocrRetryRejections = rejectionCounter(meterRegistry, "ocr-retry");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        List<LimitRequest> limits = limitsFor(request);
        for (LimitRequest limit : limits) {
            ConsumptionProbe probe = bucket(limit).tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                limit.rejectionCounter().increment();
                long nanos = probe.getNanosToWaitForRefill();
                long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(nanos)
                        + (nanos % 1_000_000_000L == 0 ? 0 : 1));
                response.setHeader("Retry-After", Long.toString(retryAfter));
                problemWriter.write(
                        request,
                        response,
                        429,
                        "RATE_LIMIT_EXCEEDED",
                        "Too many requests; retry later");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private List<LimitRequest> limitsFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (LOGIN_PATH.equals(path) && HttpMethod.POST.matches(request.getMethod())) {
            return List.of(new LimitRequest(
                    "login",
                    clientKey(request),
                    properties.login(),
                    loginRejections));
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actorKey = authentication != null && authentication.getPrincipal() instanceof DemeterPrincipal principal
                ? "tenant:" + principal.tenantId() + ":user:" + principal.userId()
                : clientKey(request);
        List<LimitRequest> limits = new ArrayList<>();
        limits.add(new LimitRequest(
                "authenticated",
                actorKey,
                properties.authenticated(),
                authenticatedRejections));
        if (OCR_TASKS_PATH.equals(path) && HttpMethod.POST.matches(request.getMethod())) {
            limits.add(new LimitRequest(
                    "ocr-upload",
                    actorKey,
                    properties.ocrUpload(),
                    ocrUploadRejections));
        } else if (path.startsWith(OCR_TASKS_PATH + "/")
                && path.endsWith("/retry")
                && HttpMethod.POST.matches(request.getMethod())) {
            limits.add(new LimitRequest(
                    "ocr-retry",
                    actorKey,
                    properties.ocrRetry(),
                    ocrRetryRejections));
        }
        return limits;
    }

    private Bucket bucket(LimitRequest request) {
        return buckets.get(request.policyName() + ":" + request.subjectKey(), ignored -> {
            RateLimitProperties.Policy policy = request.policy();
            Bandwidth bandwidth = Bandwidth.builder()
                    .capacity(policy.capacity())
                    .refillGreedy(policy.refillTokens(), policy.refillPeriod())
                    .build();
            return Bucket.builder().addLimit(bandwidth).build();
        });
    }

    private static String clientKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return "ip:" + (address == null || address.isBlank() ? "unknown" : address);
    }

    private static Counter rejectionCounter(MeterRegistry meterRegistry, String policy) {
        return Counter.builder("demeter.http.rate_limit.rejected")
                .description("Requests rejected by the in-process rate limiter")
                .tag("policy", policy)
                .register(meterRegistry);
    }

    private record LimitRequest(
            String policyName,
            String subjectKey,
            RateLimitProperties.Policy policy,
            Counter rejectionCounter) {
    }
}
