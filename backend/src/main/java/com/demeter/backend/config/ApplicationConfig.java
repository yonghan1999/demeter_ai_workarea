package com.demeter.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import com.demeter.backend.auth.application.AuthProperties;
import com.demeter.backend.auth.infrastructure.WechatProperties;
import com.demeter.backend.audit.application.AuditProperties;
import com.demeter.backend.common.idempotency.IdempotencyProperties;
import com.demeter.backend.ocr.infrastructure.OcrStorageProperties;
import com.demeter.backend.ocr.infrastructure.OcrWorkerProperties;
import com.demeter.backend.ocr.infrastructure.OcrUploadProperties;
import com.demeter.backend.ocr.infrastructure.OcrResultProperties;
import com.demeter.backend.maintenance.MaintenanceProperties;
import com.demeter.backend.payment.infrastructure.PaymentReconciliationProperties;
import com.demeter.backend.security.RateLimitProperties;
import com.demeter.backend.common.web.HttpRequestProperties;
import com.demeter.backend.common.web.PaginationProperties;
import com.demeter.backend.migration.DatabaseSchemaProperties;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "30m")
@EnableConfigurationProperties({
        WechatProperties.class,
        AuthProperties.class,
        AuditProperties.class,
        IdempotencyProperties.class,
        BusinessTimeProperties.class,
        OcrStorageProperties.class,
        OcrWorkerProperties.class,
        OcrUploadProperties.class,
        OcrResultProperties.class,
        MaintenanceProperties.class,
        PaymentReconciliationProperties.class,
        RateLimitProperties.class,
        ProductionProperties.class,
        ManagementAccessProperties.class,
        HttpRequestProperties.class,
        PaginationProperties.class,
        DatabaseSchemaProperties.class,
        RuntimeRoleProperties.class,
        SchedulingConfig.SchedulingProperties.class
})
public class ApplicationConfig {

    @Bean
    Clock clock(BusinessTimeProperties businessTime) {
        return Clock.system(businessTime.zone());
    }

    @Bean
    OpenAPI demeterOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Demeter API")
                .description("Freight billing API for the Demeter WeChat Mini Program")
                .version("v1"));
    }

    @Bean
    LockProvider schedulerLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
