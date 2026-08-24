package com.demeter.backend.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "open_id", nullable = false, unique = true, length = 128)
    private String openId;

    @Column(name = "union_id", length = 128)
    private String unionId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccount() {
    }

    public UserAccount(Long tenantId, String openId, String unionId, String displayName, Instant now) {
        this.tenantId = tenantId;
        this.openId = openId;
        this.unionId = unionId;
        this.displayName = displayName;
        this.status = UserStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getOpenId() {
        return openId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserStatus getStatus() {
        return status;
    }
}
