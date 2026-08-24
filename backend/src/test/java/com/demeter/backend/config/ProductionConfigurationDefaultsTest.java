package com.demeter.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionConfigurationDefaultsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=prod");

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
