package com.demeter.backend.common.idempotency;

import com.demeter.backend.common.error.ConflictException;
import com.demeter.backend.common.idempotency.infrastructure.BusinessCommandReplay;
import com.demeter.backend.common.idempotency.infrastructure.BusinessCommandReplayRepository;
import com.demeter.backend.security.DemeterPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class BusinessCommandReplayService {

    private final BusinessCommandReplayRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final IdempotencyProperties properties;

    public BusinessCommandReplayService(
            BusinessCommandReplayRepository repository,
            ObjectMapper objectMapper,
            Clock clock,
            IdempotencyProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
    }

    public <R> Optional<R> find(
            DemeterPrincipal actor,
            String operation,
            String idempotencyKey,
            String requestHash,
            Class<R> responseType) {
        return repository.findByTenantIdAndOperationNameAndIdempotencyKey(
                        actor.tenantId(), operation, idempotencyKey)
                .map(existing -> {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        throw new ConflictException(
                                "The Idempotency-Key was already used for a different request");
                    }
                    return deserialize(existing.getResponseJson(), responseType);
                });
    }

    public <R> Optional<R> findForUpdate(
            DemeterPrincipal actor,
            String operation,
            String idempotencyKey,
            String requestHash,
            Class<R> responseType) {
        return repository.findForUpdate(actor.tenantId(), operation, idempotencyKey)
                .map(existing -> {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        throw new ConflictException(
                                "The Idempotency-Key was already used for a different request");
                    }
                    return deserialize(existing.getResponseJson(), responseType);
                });
    }

    public void record(
            DemeterPrincipal actor,
            String operation,
            String idempotencyKey,
            String requestHash,
            Object response) {
        repository.saveAndFlush(new BusinessCommandReplay(
                actor.tenantId(),
                operation,
                idempotencyKey,
                requestHash,
                serialize(response),
                actor.userId(),
                clock.instant()));
    }

    private String serialize(Object response) {
        try {
            String serialized = objectMapper.writeValueAsString(response);
            if (serialized.getBytes(StandardCharsets.UTF_8).length > properties.maxReplayResponseBytes()) {
                throw new IllegalStateException("Idempotent response exceeds the configured size limit");
            }
            return serialized;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize an idempotent command response", exception);
        }
    }

    private <R> R deserialize(String response, Class<R> responseType) {
        try {
            return objectMapper.readValue(response, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize an idempotent command response", exception);
        }
    }
}
