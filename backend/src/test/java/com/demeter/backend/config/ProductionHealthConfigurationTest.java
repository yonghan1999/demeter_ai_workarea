package com.demeter.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionHealthConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=prod");

    @Test
    void exposesTheExpectedHealthGroupsAndManagementBoundariesInProduction() {
        contextRunner.run(context -> {
            assertThat(context.getEnvironment().getProperty("management.endpoint.health.group.readiness.include"))
                    .isEqualTo("readinessState,db,databaseSchema,roleReadiness");
            assertThat(context.getEnvironment().getProperty("management.endpoint.health.group.liveness.include"))
                    .isEqualTo("livenessState");
            assertThat(context.getEnvironment().getProperty("management.server.address")).isEqualTo("127.0.0.1");
            assertThat(context.getEnvironment().getProperty("spring.jmx.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("false");
        });
    }
}
