package com.demeter.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RuntimeRoleConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ApiComponent.class)
            .withBean(WorkerComponent.class)
            .withBean(MaintenanceComponent.class);

    @Test
    void allRoleEnablesEveryRoleSpecificComponent() {
        contextRunner.withPropertyValues("demeter.runtime.role=all").run(context -> {
            assertThat(context).hasSingleBean(ApiComponent.class);
            assertThat(context).hasSingleBean(WorkerComponent.class);
            assertThat(context).hasSingleBean(MaintenanceComponent.class);
        });
    }

    @Test
    void apiRoleDisablesBackgroundComponents() {
        contextRunner.withPropertyValues("demeter.runtime.role=api").run(context -> {
            assertThat(context).hasSingleBean(ApiComponent.class);
            assertThat(context).doesNotHaveBean(WorkerComponent.class);
            assertThat(context).doesNotHaveBean(MaintenanceComponent.class);
        });
    }

    @Test
    void workerAndMaintenanceRolesRemainIsolated() {
        contextRunner.withPropertyValues("demeter.runtime.role=worker").run(context -> {
            assertThat(context).doesNotHaveBean(ApiComponent.class);
            assertThat(context).hasSingleBean(WorkerComponent.class);
            assertThat(context).doesNotHaveBean(MaintenanceComponent.class);
        });
        contextRunner.withPropertyValues("demeter.runtime.role=maintenance").run(context -> {
            assertThat(context).doesNotHaveBean(ApiComponent.class);
            assertThat(context).doesNotHaveBean(WorkerComponent.class);
            assertThat(context).hasSingleBean(MaintenanceComponent.class);
        });
    }

    @ConditionalOnRuntimeRole(RuntimeRole.API)
    static final class ApiComponent {
    }

    @ConditionalOnRuntimeRole(RuntimeRole.WORKER)
    static final class WorkerComponent {
    }

    @ConditionalOnRuntimeRole(RuntimeRole.MAINTENANCE)
    static final class MaintenanceComponent {
    }
}
