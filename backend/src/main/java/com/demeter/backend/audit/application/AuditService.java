package com.demeter.backend.audit.application;

import com.demeter.backend.audit.domain.AuditEvent;
import com.demeter.backend.audit.infrastructure.AuditEventRepository;
import com.demeter.backend.security.DemeterPrincipal;
import com.demeter.backend.admin.security.AdminPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AuditProperties properties;

    public AuditService(
            AuditEventRepository repository,
            ObjectMapper objectMapper,
            Clock clock,
            AuditProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
    }

    public void record(
            DemeterPrincipal actor,
            String action,
            String aggregateType,
            Object aggregateId,
            Object details) {
        repository.save(new AuditEvent(
                actor.tenantId(),
                actor.userId(),
                action,
                aggregateType,
                String.valueOf(aggregateId),
                MDC.get("requestId"),
                serialize(details),
                clock.instant()));
    }

    private Object enrichSystemDetails(Object details) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AdminPrincipal admin)) {
            return details;
        }
        java.util.Map<String, Object> enriched = new java.util.LinkedHashMap<>();
        if (details instanceof java.util.Map<?, ?> map) {
            map.forEach((key, value) -> enriched.put(String.valueOf(key), value));
        } else if (details != null) {
            enriched.put("details", details);
        }
        enriched.put("operator", admin.username());
        return enriched;
    }

    public void recordSystem(
            long tenantId,
            String action,
            String aggregateType,
            Object aggregateId,
            Object details) {
        repository.save(new AuditEvent(
                tenantId,
                null,
                action,
                aggregateType,
                String.valueOf(aggregateId),
                MDC.get("requestId"),
                serialize(enrichSystemDetails(details)),
                clock.instant()));
    }

    private String serialize(Object details) {
        if (details == null) {
            return null;
        }
        try {
            String serialized = objectMapper.writeValueAsString(details);
            if (serialized.getBytes(StandardCharsets.UTF_8).length > properties.maxDetailsBytes()) {
                throw new IllegalStateException("Audit details exceed the configured size limit");
            }
            return serialized;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit details", exception);
        }
    }
}
