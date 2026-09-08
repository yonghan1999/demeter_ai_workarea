package com.demeter.backend.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    @Column(nullable = false, length = 80, updatable = false)
    private String action;

    @Column(name = "aggregate_type", nullable = false, length = 80, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 80, updatable = false)
    private String aggregateId;

    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    @Column(columnDefinition = "MEDIUMTEXT", updatable = false)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(
            Long tenantId,
            Long actorUserId,
            String action,
            String aggregateType,
            String aggregateId,
            String requestId,
            String details,
            Instant createdAt) {
        this.tenantId = tenantId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.requestId = requestId;
        this.details = details;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public Long getActorUserId() { return actorUserId; }
    public String getAction() { return action; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getRequestId() { return requestId; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
