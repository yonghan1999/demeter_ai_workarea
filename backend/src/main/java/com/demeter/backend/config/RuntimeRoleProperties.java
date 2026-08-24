package com.demeter.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demeter.runtime")
public record RuntimeRoleProperties(RuntimeRole role) {

    public RuntimeRoleProperties {
        if (role == null) {
            role = RuntimeRole.ALL;
        }
    }

    public boolean runsApi() {
        return role == RuntimeRole.ALL || role == RuntimeRole.API;
    }

    public boolean runsWorker() {
        return role == RuntimeRole.ALL || role == RuntimeRole.WORKER;
    }

    public boolean runsMaintenance() {
        return role == RuntimeRole.ALL || role == RuntimeRole.MAINTENANCE;
    }
}
