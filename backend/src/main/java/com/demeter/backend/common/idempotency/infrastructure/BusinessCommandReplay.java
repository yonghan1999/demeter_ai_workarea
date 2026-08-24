package com.demeter.backend.common.idempotency.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "business_command_replays")
public class BusinessCommandReplay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "operation_name", nullable = false, length = 80, updatable = false)
    private String operationName;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "response_json", nullable = false, columnDefinition = "MEDIUMTEXT", updatable = false)
    private String responseJson;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BusinessCommandReplay() {
    }

    public BusinessCommandReplay(
            Long tenantId,
            String operationName,
            String idempotencyKey,
            String requestHash,
            String responseJson,
            Long createdBy,
            Instant createdAt) {
        this.tenantId = tenantId;
        this.operationName = operationName;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.responseJson = responseJson;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseJson() {
        return responseJson;
    }
}
