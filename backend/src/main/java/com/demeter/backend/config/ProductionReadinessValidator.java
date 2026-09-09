package com.demeter.backend.config;

import com.demeter.backend.auth.infrastructure.WechatProperties;
import com.demeter.backend.maintenance.MaintenanceProperties;
import com.demeter.backend.ocr.infrastructure.OcrStorageProperties;
import com.demeter.backend.ocr.infrastructure.OcrWorkerProperties;
import com.demeter.backend.ocr.spi.HandwrittenBillOcrProvider;
import com.demeter.backend.payment.infrastructure.PaymentReconciliationProperties;
import com.demeter.backend.security.RateLimitProperties;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile("prod")
public class ProductionReadinessValidator implements InitializingBean {

    private static final String API_DATABASE_USER = "demeter_api";
    private static final String WORKER_DATABASE_USER = "demeter_worker";
    private static final String MAINTENANCE_DATABASE_USER = "demeter_maintenance";
    private static final Set<String> PRIVILEGED_DATABASE_USERS = Set.of(
            "root", "mysql", "admin", "administrator", "demeter_migrator");

    private final Environment environment;
    private final WechatProperties wechat;
    private final OcrStorageProperties storage;
    private final RateLimitProperties rateLimit;
    private final ProductionProperties production;
    private final OcrWorkerProperties worker;
    private final MaintenanceProperties maintenance;
    private final PaymentReconciliationProperties reconciliation;
    private final RuntimeRoleProperties runtime;
    private final ManagementAccessProperties management;
    private final AdminProperties admin;
    private final List<HandwrittenBillOcrProvider> ocrProviders;

    public ProductionReadinessValidator(
            Environment environment,
            WechatProperties wechat,
            OcrStorageProperties storage,
            RateLimitProperties rateLimit,
            ProductionProperties production,
            OcrWorkerProperties worker,
            MaintenanceProperties maintenance,
            PaymentReconciliationProperties reconciliation,
            RuntimeRoleProperties runtime,
            ManagementAccessProperties management,
            AdminProperties admin,
            List<HandwrittenBillOcrProvider> ocrProviders) {
        this.environment = environment;
        this.wechat = wechat;
        this.storage = storage;
        this.rateLimit = rateLimit;
        this.production = production;
        this.worker = worker;
        this.maintenance = maintenance;
        this.reconciliation = reconciliation;
        this.runtime = runtime;
        this.management = management;
        this.admin = admin;
        this.ocrProviders = List.copyOf(ocrProviders);
    }

    @Override
    public void afterPropertiesSet() {
        if (runtime.role() == RuntimeRole.ALL) {
            throw new IllegalStateException(
                    "DEMETER_RUNTIME_ROLE must be API, WORKER, or MAINTENANCE in production");
        }
        requireText(environment.getProperty("spring.datasource.url"), "DB_URL is required");
        String username = requireText(
                environment.getProperty("spring.datasource.username"), "DB_USERNAME is required");
        if (PRIVILEGED_DATABASE_USERS.contains(username.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("DB_USERNAME must use a dedicated non-privileged application account");
        }
        String password = requireText(
                environment.getProperty("spring.datasource.password"), "DB_PASSWORD is required");
        if (password.length() < 16 || isPlaceholder(password)) {
            throw new IllegalStateException("DB_PASSWORD must not use a development value");
        }
        validateDatabaseUserForRole(username);

        String databaseUrl = environment.getRequiredProperty("spring.datasource.url");
        if (!databaseUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql:")) {
            throw new IllegalStateException("Production DB_URL must use MySQL");
        }
        if (!hasVerifyIdentity(databaseUrl)) {
            throw new IllegalStateException("Production DB_URL must set sslMode=VERIFY_IDENTITY");
        }
        if (containsParameter(databaseUrl, "allowPublicKeyRetrieval", "true")) {
            throw new IllegalStateException("Production DB_URL must not enable allowPublicKeyRetrieval");
        }
        if (containsParameter(databaseUrl, "useSSL", "false")
                || containsParameter(databaseUrl, "sslMode", "DISABLED")) {
            throw new IllegalStateException("Production DB_URL must not disable TLS");
        }
        if (!containsParameter(databaseUrl, "connectionTimeZone", "UTC")
                && !containsParameter(databaseUrl, "serverTimezone", "UTC")) {
            throw new IllegalStateException("Production DB_URL must use the UTC connection time zone");
        }

        int serverPort = environment.getProperty("server.port", Integer.class, 8080);
        int managementPort = environment.getProperty("management.server.port", Integer.class, serverPort);
        if (serverPort == managementPort) {
            throw new IllegalStateException("MANAGEMENT_PORT must differ from SERVER_PORT in production");
        }
        String managementAddress = environment.getProperty("management.server.address", "127.0.0.1");
        if (!isLoopbackAddress(managementAddress)) {
            throw new IllegalStateException("MANAGEMENT_ADDRESS must bind to a loopback address in production");
        }
        String managementToken = requireSecret(management.accessToken(), "MANAGEMENT_ACCESS_TOKEN");
        if (managementToken.length() < 32) {
            throw new IllegalStateException("MANAGEMENT_ACCESS_TOKEN must contain at least 32 characters");
        }
        if (storage.root() == null || !storage.root().isAbsolute()) {
            throw new IllegalStateException("OCR_STORAGE_PATH must be an absolute path in production");
        }
        if (production.replicaCount() > 1 && !storage.shared()) {
            throw new IllegalStateException("Multi-replica deployments require shared OCR document storage");
        }
        requireText(environment.getProperty("server.tomcat.mbeanregistry.enabled"),
                "Tomcat production hardening configuration is missing");
        if (environment.getProperty("server.tomcat.mbeanregistry.enabled", Boolean.class, true)) {
            throw new IllegalStateException("Tomcat MBean registry must be disabled in production");
        }
        if (environment.getProperty("spring.jmx.enabled", Boolean.class, true)) {
            throw new IllegalStateException("Spring JMX must be disabled in production");
        }
        if (environment.getProperty("spring.flyway.enabled", Boolean.class, true)) {
            throw new IllegalStateException(
                    "Flyway must be disabled in production runtime processes; run the dedicated migration job first");
        }
        if (environment.getProperty("springdoc.api-docs.enabled", Boolean.class, false)
                || environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, false)) {
            throw new IllegalStateException("OpenAPI and Swagger UI must be disabled in production");
        }
        String ddlMode = environment.getProperty("spring.jpa.hibernate.ddl-auto", "none");
        if (!"none".equalsIgnoreCase(ddlMode)) {
            throw new IllegalStateException(
                    "Hibernate schema management must be disabled in production runtime processes");
        }
        validateRole();
    }

    private void validateRole() {
        if (admin.enabled() && runtime.role() != RuntimeRole.API) {
            throw new IllegalStateException("ADMIN_ENABLED may only be true for the API role");
        }
        switch (runtime.role()) {
            case API -> validateApiRole();
            case WORKER -> validateWorkerRole();
            case MAINTENANCE -> validateMaintenanceRole();
            case ALL -> throw new IllegalStateException("ALL is not a production runtime role");
        }
    }

    private void validateDatabaseUserForRole(String username) {
        String expected = switch (runtime.role()) {
            case API -> API_DATABASE_USER;
            case WORKER -> WORKER_DATABASE_USER;
            case MAINTENANCE -> MAINTENANCE_DATABASE_USER;
            case ALL -> null;
        };
        if (expected != null && !expected.equals(username)) {
            throw new IllegalStateException(
                    "DB_USERNAME must be " + expected + " when DEMETER_RUNTIME_ROLE is " + runtime.role());
        }
    }

    private void validateApiRole() {
        if (worker.enabled()) {
            throw new IllegalStateException("OCR_WORKER_ENABLED must be false for the API role");
        }
        if (maintenance.enabled() || reconciliation.enabled()) {
            throw new IllegalStateException(
                    "MAINTENANCE_ENABLED and PAYMENT_RECONCILIATION_ENABLED must be false for the API role");
        }
        requireSecret(wechat.appId(), "WECHAT_APP_ID");
        requireSecret(wechat.appSecret(), "WECHAT_APP_SECRET");
        String wechatUrl = requireText(wechat.codeToSessionUrl(), "WECHAT_CODE_TO_SESSION_URL is required");
        if (!wechatUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalStateException("WECHAT_CODE_TO_SESSION_URL must use HTTPS");
        }
        String forwardStrategy = environment.getProperty("server.forward-headers-strategy", "none");
        if (!"native".equalsIgnoreCase(forwardStrategy)) {
            throw new IllegalStateException("Production API must use native trusted-proxy header processing");
        }
        String trustedProxyPattern = requireText(
                environment.getProperty("server.tomcat.remoteip.internal-proxies"),
                "TRUSTED_PROXY_PATTERN is required for the API role");
        if ("(?!)".equals(trustedProxyPattern)) {
            throw new IllegalStateException("TRUSTED_PROXY_PATTERN is required for the API role");
        }
        validateTrustedProxyPattern(trustedProxyPattern);
        if (production.replicaCount() > 1 && !production.gatewayRateLimitEnabled()) {
            throw new IllegalStateException("Multi-replica API deployments require gateway-level distributed rate limiting");
        }
        if (!rateLimit.enabled()) {
            throw new IllegalStateException("In-process rate limiting must remain enabled on the production API");
        }
        if (admin.enabled()) {
            String adminToken = requireSecret(admin.accessToken(), "ADMIN_ACCESS_TOKEN");
            if (adminToken.equals(management.accessToken())) {
                throw new IllegalStateException("ADMIN_ACCESS_TOKEN must differ from MANAGEMENT_ACCESS_TOKEN");
            }
        }
    }

    private void validateWorkerRole() {
        if (!worker.enabled()) {
            throw new IllegalStateException("OCR_WORKER_ENABLED must be true for the WORKER role");
        }
        if (maintenance.enabled() || reconciliation.enabled()) {
            throw new IllegalStateException(
                    "MAINTENANCE_ENABLED and PAYMENT_RECONCILIATION_ENABLED must be false for the WORKER role");
        }
        java.time.Duration providerTimeout = environment.getProperty(
                "resilience4j.timelimiter.instances.ocrProvider.timeout-duration",
                java.time.Duration.class,
                java.time.Duration.ofSeconds(20));
        if (worker.leaseDuration().compareTo(providerTimeout.multipliedBy(2)) < 0) {
            throw new IllegalStateException("OCR lease duration must be at least twice the provider timeout");
        }
        if (ocrProviders.size() != 1) {
            throw new IllegalStateException("The WORKER role requires exactly one OCR provider");
        }
        requireLoopbackApplicationAddress();
        if (!storage.shared()) {
            throw new IllegalStateException("The WORKER role requires shared OCR document storage");
        }
    }

    private void validateMaintenanceRole() {
        if (worker.enabled()) {
            throw new IllegalStateException("OCR_WORKER_ENABLED must be false for the MAINTENANCE role");
        }
        if (!maintenance.enabled() && !reconciliation.enabled()) {
            throw new IllegalStateException(
                    "The MAINTENANCE role must enable maintenance or payment reconciliation");
        }
        requireLoopbackApplicationAddress();
        if (maintenance.enabled() && !storage.shared()) {
            throw new IllegalStateException("OCR maintenance requires shared OCR document storage");
        }
    }

    private void requireLoopbackApplicationAddress() {
        String address = requireText(
                environment.getProperty("server.address"),
                "SERVER_ADDRESS is required for non-API production roles");
        if (!isLoopbackAddress(address)) {
            throw new IllegalStateException(
                    "Non-API production roles must bind SERVER_ADDRESS to a loopback address");
        }
    }

    private static boolean isLoopbackAddress(String address) {
        return address != null && Set.of("127.0.0.1", "::1", "localhost").contains(address.toLowerCase(Locale.ROOT));
    }

    private static boolean hasVerifyIdentity(String databaseUrl) {
        String normalized = databaseUrl.toLowerCase(Locale.ROOT).replace(" ", "");
        return normalized.contains("sslmode=verify_identity");
    }

    private static boolean containsParameter(String databaseUrl, String name, String value) {
        String normalized = databaseUrl.toLowerCase(Locale.ROOT).replace(" ", "");
        return normalized.matches(".*[?&]" + java.util.regex.Pattern.quote(name.toLowerCase(Locale.ROOT))
                + "=" + java.util.regex.Pattern.quote(value.toLowerCase(Locale.ROOT)) + "(?:&.*)?$");
    }

    private static void validateTrustedProxyPattern(String value) {
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(value);
            if (pattern.matcher("8.8.8.8").matches() && pattern.matcher("203.0.113.10").matches()) {
                throw new IllegalStateException("TRUSTED_PROXY_PATTERN must not trust arbitrary public addresses");
            }
        } catch (PatternSyntaxException exception) {
            throw new IllegalStateException("TRUSTED_PROXY_PATTERN must be a valid regular expression", exception);
        }
    }

    private static String requireSecret(String value, String name) {
        String secret = requireText(value, name + " is required");
        if (isPlaceholder(secret)) {
            throw new IllegalStateException(name + " must not use a placeholder value");
        }
        return secret;
    }

    private static boolean isPlaceholder(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return "demeter".equals(normalized)
                || normalized.contains("replace-with")
                || normalized.contains("change-me");
    }

    private static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }
}
