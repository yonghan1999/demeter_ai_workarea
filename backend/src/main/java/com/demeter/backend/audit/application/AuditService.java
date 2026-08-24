package com.demeter.backend.audit.application;

import com.demeter.backend.audit.domain.AuditEvent;
import com.demeter.backend.audit.infrastructure.AuditEventRepository;
import com.demeter.backend.security.DemeterPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

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
