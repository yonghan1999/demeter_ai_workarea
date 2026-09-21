package com.demeter.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionConfigurationDefaultsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=prod");

    @Test
    void bindsProductionHikariTimeoutDefaultsInMilliseconds() {
        contextRunner.run(context -> {
            HikariConfig config = Binder.get(context.getEnvironment())
                    .bind("spring.datasource.hikari", Bindable.of(HikariConfig.class)).get();

            assertThat(config.getConnectionTimeout()).isEqualTo(10000);
            assertThat(config.getValidationTimeout()).isEqualTo(3000);
            assertThat(config.getIdleTimeout()).isEqualTo(600000);
            assertThat(config.getMaxLifetime()).isEqualTo(1800000);
            assertThat(config.getKeepaliveTime()).isEqualTo(120000);
        });
    }

    @Test
    void bindsProductionHikariTimeoutOverridesInMilliseconds() {
        contextRunner.withPropertyValues(
                "DB_CONNECTION_TIMEOUT=15000", "DB_VALIDATION_TIMEOUT=5000",
                "DB_IDLE_TIMEOUT=300000", "DB_MAX_LIFETIME=900000", "DB_KEEPALIVE_TIME=60000")
                .run(context -> {
                    HikariConfig config = Binder.get(context.getEnvironment())
                            .bind("spring.datasource.hikari", Bindable.of(HikariConfig.class)).get();

                    assertThat(config.getConnectionTimeout()).isEqualTo(15000);
                    assertThat(config.getValidationTimeout()).isEqualTo(5000);
                    assertThat(config.getIdleTimeout()).isEqualTo(300000);
                    assertThat(config.getMaxLifetime()).isEqualTo(900000);
                    assertThat(config.getKeepaliveTime()).isEqualTo(60000);
                });
    }

    @Test
    void disablesSwaggerAndOpenApiInTheProductionProfile() {
        contextRunner.run(context -> {
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy")).isEqualTo("native");
            assertThat(context.getEnvironment().getProperty("server.tomcat.mbeanregistry.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("spring.jmx.enabled")).isEqualTo("false");
        });
    }
}
