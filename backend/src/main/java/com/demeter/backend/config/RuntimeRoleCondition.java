package com.demeter.backend.config;

import java.util.Collection;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Enables role-specific components while keeping the local all-in-one profile convenient. */
public final class RuntimeRoleCondition implements Condition {

    private RuntimeRoleCondition() {
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String role = context.getEnvironment().getProperty("demeter.runtime.role", "ALL");
        Object configured = metadata
                .getAnnotationAttributes(ConditionalOnRuntimeRole.class.getName())
                .get("value");
        Collection<?> allowed = configured instanceof Object[] values
                ? Arrays.asList(values)
                : (Collection<?>) configured;
        RuntimeRole actual = RuntimeRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        return actual == RuntimeRole.ALL || allowed.stream().anyMatch(value ->
                actual == RuntimeRole.valueOf(value.toString().toUpperCase(Locale.ROOT)));
    }
}
