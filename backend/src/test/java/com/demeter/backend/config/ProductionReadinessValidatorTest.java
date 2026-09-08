package com.demeter.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demeter.backend.auth.infrastructure.WechatProperties;
import com.demeter.backend.maintenance.MaintenanceProperties;
import com.demeter.backend.ocr.infrastructure.OcrStorageProperties;
import com.demeter.backend.ocr.infrastructure.OcrWorkerProperties;
import com.demeter.backend.ocr.spi.HandwrittenBillOcrProvider;
import com.demeter.backend.payment.infrastructure.PaymentReconciliationProperties;
import com.demeter.backend.security.RateLimitProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionReadinessValidatorTest {

    private static final Path ABSOLUTE_STORAGE_PATH = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();

    private static final WechatProperties WECHAT = new WechatProperties(
            "production-app-id",
            "production-secret",
            "https://api.weixin.qq.com/sns/jscode2session",
            Duration.ofSeconds(3),
            Duration.ofSeconds(5));

    private static final RateLimitProperties RATE_LIMIT = new RateLimitProperties(
            true,
            1000,
            Duration.ofMinutes(15),
            policy(),
            policy(),
            policy(),
            policy());

    @Test
    void rejectsReusingTheManagementSecretForTheAdminConsole() {
        MockEnvironment environment = productionEnvironment(
                "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                "demeter_api",
                false);
        ProductionReadinessValidator validator = new ProductionReadinessValidator(
                environment,
                WECHAT,
                new OcrStorageProperties(ABSOLUTE_STORAGE_PATH, false),
                RATE_LIMIT,
                new ProductionProperties(1, false),
                disabledWorker(),
                new MaintenanceProperties(false, Duration.ofDays(7), Duration.ofDays(90), Duration.ofDays(180),
                        Duration.ofHours(2), Duration.ofDays(90), Duration.ofDays(30), Duration.ofHours(2), 100, 10, 1000),
                new PaymentReconciliationProperties(false, 20),
                new RuntimeRoleProperties(RuntimeRole.API),
                new ManagementAccessProperties("0123456789abcdef0123456789abcdef"),
                new AdminProperties(true, "0123456789abcdef0123456789abcdef", Duration.ofHours(8)),
                java.util.List.of());

        assertThatThrownBy(validator::afterPropertiesSet)
                .hasMessageContaining("ADMIN_ACCESS_TOKEN must differ");
    }

    @Test
    void rejectsAMySqlConnectionWithoutCertificateAndHostnameVerification() {
        ProductionReadinessValidator validator = validator(
                "jdbc:mysql://db:3306/demeter?sslMode=REQUIRED&connectionTimeZone=UTC",
                new ProductionProperties(1, false),
                new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sslMode=VERIFY_IDENTITY");
    }

    @Test
    void rejectsMultipleReplicasWithoutSharedStorageAndGatewayRateLimiting() {
        ProductionReadinessValidator validator = validator(
                "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                new ProductionProperties(2, false),
                new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared OCR document storage");
    }

    @Test
    void acceptsACompleteSingleReplicaProductionConfiguration() {
        ProductionReadinessValidator validator = validator(
                "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                new ProductionProperties(1, false),
                new OcrStorageProperties(ABSOLUTE_STORAGE_PATH, false));

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsBackgroundJobsInsideTheProductionApiRole() {
        String url = "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC";

        assertThatThrownBy(() -> validator(
                        url,
                        new ProductionProperties(1, false),
                        new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false),
                        RuntimeRole.API,
                        enabledWorker(),
                        false,
                        false,
                        java.util.List.of(),
                        false)
                .afterPropertiesSet())
                .hasMessageContaining("OCR_WORKER_ENABLED must be false for the API role");

        assertThatThrownBy(() -> validator(
                        url,
                        new ProductionProperties(1, false),
                        new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false),
                        RuntimeRole.API,
                        disabledWorker(),
                        true,
                        false,
                        java.util.List.of(),
                        false)
                .afterPropertiesSet())
                .hasMessageContaining("PAYMENT_RECONCILIATION_ENABLED must be false for the API role");
    }

    @Test
    void rejectsARelativeOcrStoragePath() {
        ProductionReadinessValidator validator = validator(
                "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                new ProductionProperties(1, false),
                new OcrStorageProperties(Path.of("data/ocr"), false));

        assertThatThrownBy(validator::afterPropertiesSet)
                .hasMessageContaining("OCR_STORAGE_PATH must be an absolute path");
    }

    @Test
    void rejectsFlywayInsideTheProductionApiProcess() {
        ProductionReadinessValidator validator = validator(
                "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                new ProductionProperties(1, false),
                new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false),
                true);

        assertThatThrownBy(validator::afterPropertiesSet)
                .hasMessageContaining("dedicated migration job");
    }

    @Test
    void rejectsOpenApiAndSwaggerInProduction() {
        String url = "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC";
        MockEnvironment environment = productionEnvironment(url, "demeter_api", false);
        environment.setProperty("springdoc.api-docs.enabled", "true");
        environment.setProperty("springdoc.swagger-ui.enabled", "true");

        ProductionReadinessValidator validator = validator(
                environment,
                new ProductionProperties(1, false),
                new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false),
                RuntimeRole.API,
                disabledWorker(),
                false,
                false,
                java.util.List.of());

        assertThatThrownBy(validator::afterPropertiesSet)
                .hasMessageContaining("OpenAPI and Swagger UI must be disabled");
    }

    @Test
    void rejectsPublicManagementBindingInProduction() {
        String url = "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC";
        MockEnvironment environment = productionEnvironment(url, "demeter_api", false);
        environment.setProperty("management.server.address", "0.0.0.0");

        ProductionReadinessValidator validator = validator(
                environment,
                new ProductionProperties(1, false),
                new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false),
                RuntimeRole.API,
                disabledWorker(),
                false,
                false,
                java.util.List.of());

        assertThatThrownBy(validator::afterPropertiesSet)
                .hasMessageContaining("MANAGEMENT_ADDRESS must bind to a loopback address");
    }

    @Test
    void rejectsTheAllInOneRoleInProduction() {
        ProductionReadinessValidator validator = validator(
                "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                new ProductionProperties(1, false),
                new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false),
                RuntimeRole.ALL,
                disabledWorker(),
                false,
                false,
                java.util.List.of(),
                false);

        assertThatThrownBy(validator::afterPropertiesSet)
                .hasMessageContaining("DEMETER_RUNTIME_ROLE");
    }

    @Test
    void requiresAnEnabledWorkerAndExactlyOneProviderForTheWorkerRole() {
        String url = "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC";
        ProductionProperties production = new ProductionProperties(1, false);
        OcrStorageProperties storage = new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), true);

        assertThatThrownBy(() -> validator(
                        url,
                        production,
                        storage,
                        RuntimeRole.WORKER,
                        disabledWorker(),
                        false,
                        false,
                        java.util.List.of(),
                        false)
                .afterPropertiesSet())
                .hasMessageContaining("OCR_WORKER_ENABLED");

        assertThatThrownBy(() -> validator(
                        url,
                        production,
                        storage,
                        RuntimeRole.WORKER,
                        enabledWorker(),
                        false,
                        false,
                        java.util.List.of(),
                        false)
                .afterPropertiesSet())
                .hasMessageContaining("exactly one OCR provider");

        HandwrittenBillOcrProvider provider = request -> null;
        assertThatCode(() -> validator(
                        url,
                        production,
                        storage,
                        RuntimeRole.WORKER,
                        enabledWorker(),
                        false,
                        false,
                        java.util.List.of(provider),
                        false)
                .afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMaintenanceJobsInsideTheWorkerRole() {
        String url = "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC";
        HandwrittenBillOcrProvider provider = request -> null;

        assertThatThrownBy(() -> validator(
                        url,
                        new ProductionProperties(1, false),
                        new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), true),
                        RuntimeRole.WORKER,
                        enabledWorker(),
                        true,
                        false,
                        java.util.List.of(provider),
                        false)
                .afterPropertiesSet())
                .hasMessageContaining("PAYMENT_RECONCILIATION_ENABLED must be false for the WORKER role");
    }

    @Test
    void requiresSharedStorageAndALoopbackApplicationPortForTheWorkerRole() {
        String url = "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC";
        HandwrittenBillOcrProvider provider = request -> null;

        assertThatThrownBy(() -> validator(
                        url,
                        new ProductionProperties(1, false),
                        new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false),
                        RuntimeRole.WORKER,
                        enabledWorker(),
                        false,
                        false,
                        java.util.List.of(provider),
                        false)
                .afterPropertiesSet())
                .hasMessageContaining("shared OCR document storage");

        MockEnvironment publicAddress = productionEnvironment(
                url,
                "demeter_worker",
                false);
        publicAddress.setProperty("server.address", "0.0.0.0");
        ProductionReadinessValidator validator = validator(
                publicAddress,
                new ProductionProperties(1, false),
                new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), true),
                RuntimeRole.WORKER,
                enabledWorker(),
                false,
                false,
                java.util.List.of(provider));

        assertThatThrownBy(validator::afterPropertiesSet)
                .hasMessageContaining("loopback address");
    }

    @Test
    void rejectsAnIdleMaintenanceRole() {
        ProductionReadinessValidator validator = validator(
                "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                new ProductionProperties(1, false),
                new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false),
                RuntimeRole.MAINTENANCE,
                disabledWorker(),
                false,
                false,
                java.util.List.of(),
                false);

        assertThatThrownBy(validator::afterPropertiesSet)
                .hasMessageContaining("must enable maintenance or payment reconciliation");
    }

    @Test
    void rejectsOcrWorkerInsideTheMaintenanceRole() {
        ProductionReadinessValidator validator = validator(
                "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                new ProductionProperties(1, false),
                new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), true),
                RuntimeRole.MAINTENANCE,
                enabledWorker(),
                true,
                false,
                java.util.List.of(),
                false);

        assertThatThrownBy(validator::afterPropertiesSet)
                .hasMessageContaining("OCR_WORKER_ENABLED must be false for the MAINTENANCE role");
    }

    @Test
    void acceptsMaintenanceWithSharedStorageAndNoWechatCredentials() {
        ProductionReadinessValidator validator = validator(
                "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                new ProductionProperties(1, false),
                new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), true),
                RuntimeRole.MAINTENANCE,
                disabledWorker(),
                true,
                true,
                java.util.List.of(),
                false);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsPlaceholderDatabasePasswordsAndHibernateSchemaManagement() {
        String url = "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC";
        MockEnvironment placeholderPassword = productionEnvironment(url, "demeter_api", false);
        placeholderPassword.setProperty("spring.datasource.password", "local-api-password-change-me");

        assertThatThrownBy(() -> validator(
                        placeholderPassword,
                        new ProductionProperties(1, false),
                        new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false),
                        RuntimeRole.API,
                        disabledWorker(),
                        false,
                        false,
                        java.util.List.of())
                .afterPropertiesSet())
                .hasMessageContaining("development value");

        MockEnvironment hibernateValidation = productionEnvironment(url, "demeter_api", false);
        hibernateValidation.setProperty("spring.jpa.hibernate.ddl-auto", "validate");

        assertThatThrownBy(() -> validator(
                        hibernateValidation,
                        new ProductionProperties(1, false),
                        new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false),
                        RuntimeRole.API,
                        disabledWorker(),
                        false,
                        false,
                        java.util.List.of())
                .afterPropertiesSet())
                .hasMessageContaining("schema management must be disabled");
    }

    @Test
    void rejectsADatabaseAccountThatDoesNotMatchTheRuntimeRole() {
        MockEnvironment environment = productionEnvironment(
                "jdbc:mysql://db:3306/demeter?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC",
                "demeter_worker",
                false);
        ProductionReadinessValidator validator = validator(
                environment,
                new ProductionProperties(1, false),
                new OcrStorageProperties(Path.of("/var/lib/demeter/ocr"), false),
                RuntimeRole.API,
                disabledWorker(),
                false,
                false,
                java.util.List.of());

        assertThatThrownBy(validator::afterPropertiesSet)
                .hasMessageContaining("DB_USERNAME must be demeter_api");
    }

    private static ProductionReadinessValidator validator(
            String databaseUrl,
            ProductionProperties production,
            OcrStorageProperties storage) {
        return validator(databaseUrl, production, storage, false);
    }

    private static ProductionReadinessValidator validator(
            String databaseUrl,
            ProductionProperties production,
            OcrStorageProperties storage,
            boolean flywayEnabled) {
        return validator(
                databaseUrl,
                production,
                storage,
                RuntimeRole.API,
                disabledWorker(),
                false,
                false,
                java.util.List.of(),
                flywayEnabled);
    }

    private static ProductionReadinessValidator validator(
            String databaseUrl,
            ProductionProperties production,
            OcrStorageProperties storage,
            RuntimeRole role,
            OcrWorkerProperties worker,
            boolean maintenanceEnabled,
            boolean reconciliationEnabled,
            java.util.List<HandwrittenBillOcrProvider> providers,
            boolean flywayEnabled) {
        MockEnvironment environment = productionEnvironment(databaseUrl, databaseUsername(role), flywayEnabled);
        return validator(
                environment,
                production,
                storage,
                role,
                worker,
                maintenanceEnabled,
                reconciliationEnabled,
                providers);
    }

    private static ProductionReadinessValidator validator(
            MockEnvironment environment,
            ProductionProperties production,
            OcrStorageProperties storage,
            RuntimeRole role,
            OcrWorkerProperties worker,
            boolean maintenanceEnabled,
            boolean reconciliationEnabled,
            java.util.List<HandwrittenBillOcrProvider> providers) {
        OcrStorageProperties portableStorage = storage.root().isAbsolute() || !storage.root().startsWith("/")
                ? storage
                : new OcrStorageProperties(ABSOLUTE_STORAGE_PATH, storage.shared());
        return new ProductionReadinessValidator(
                environment,
                WECHAT,
                portableStorage,
                RATE_LIMIT,
                production,
                worker,
                new MaintenanceProperties(
                        maintenanceEnabled,
                        Duration.ofDays(7),
                        Duration.ofDays(90),
                        Duration.ofDays(180),
                        Duration.ofHours(2),
                        Duration.ofDays(90),
                        Duration.ofDays(30),
                        Duration.ofHours(2),
                        100,
                        10,
                        1000),
                new PaymentReconciliationProperties(reconciliationEnabled, 20),
                new RuntimeRoleProperties(role),
                new ManagementAccessProperties(
                        "0123456789abcdef0123456789abcdef"),
                new AdminProperties(false, null, Duration.ofHours(8)),
                providers);
    }

    private static MockEnvironment productionEnvironment(
            String databaseUrl,
            String databaseUsername,
            boolean flywayEnabled) {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", databaseUrl)
                .withProperty("spring.datasource.username", databaseUsername)
                .withProperty("spring.datasource.password", "strong-production-password")
                .withProperty("server.port", "8080")
                .withProperty("server.address", "127.0.0.1")
                .withProperty("management.server.port", "9090")
                .withProperty("management.server.address", "127.0.0.1")
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.internal-proxies", "10\\.0\\.0\\.1")
                .withProperty("server.tomcat.mbeanregistry.enabled", "false")
                .withProperty("spring.jmx.enabled", "false")
                .withProperty("spring.jpa.hibernate.ddl-auto", "none")
                .withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("springdoc.swagger-ui.enabled", "false")
                .withProperty("spring.flyway.enabled", Boolean.toString(flywayEnabled));
    }

    private static String databaseUsername(RuntimeRole role) {
        return switch (role) {
            case API -> "demeter_api";
            case WORKER -> "demeter_worker";
            case MAINTENANCE -> "demeter_maintenance";
            case ALL -> "demeter_api";
        };
    }

    private static OcrWorkerProperties disabledWorker() {
        return worker(false);
    }

    private static OcrWorkerProperties enabledWorker() {
        return worker(true);
    }

    private static OcrWorkerProperties worker(boolean enabled) {
        return new OcrWorkerProperties(
                enabled,
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofMinutes(15),
                3,
                20);
    }

    private static RateLimitProperties.Policy policy() {
        return new RateLimitProperties.Policy(10, 10, Duration.ofMinutes(1));
    }
}
