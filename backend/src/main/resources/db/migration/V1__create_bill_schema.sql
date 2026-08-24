CREATE TABLE tenants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenants_public_id UNIQUE (public_id),
    CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    open_id VARCHAR(128) NOT NULL,
    union_id VARCHAR(128) NULL,
    display_name VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_open_id UNIQUE (open_id),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_users_tenant ON users (tenant_id);
CREATE INDEX idx_users_union_id ON users (union_id);

CREATE TABLE auth_sessions (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_auth_sessions_user ON auth_sessions (user_id);
CREATE INDEX idx_auth_sessions_expiry ON auth_sessions (expires_at);

CREATE TABLE bill_code_sequences (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    sequence_name VARCHAR(40) NOT NULL,
    next_value BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bill_code_sequences_tenant_name UNIQUE (tenant_id, sequence_name),
    CONSTRAINT fk_bill_code_sequences_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE bills (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    shipper VARCHAR(120) NOT NULL,
    shipper_normalized VARCHAR(120) NOT NULL,
    vehicle_cargo VARCHAR(120) NULL,
    transport_date DATE NOT NULL,
    origin VARCHAR(64) NOT NULL,
    destination VARCHAR(64) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    due_date DATE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    deleted_by BIGINT NULL,
    delete_reason VARCHAR(240) NULL,
    deleted_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bills_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_bills_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_bills_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_bills_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT fk_bills_deleted_by FOREIGN KEY (deleted_by) REFERENCES users (id),
    CONSTRAINT ck_bills_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_bills_status CHECK (status IN ('UNPAID', 'PARTIALLY_PAID', 'PAID')),
    CONSTRAINT ck_bills_route CHECK (origin <> destination)
);

CREATE INDEX idx_bills_tenant_status_date ON bills (tenant_id, status, transport_date);
CREATE INDEX idx_bills_tenant_transport_date ON bills (tenant_id, transport_date);
CREATE INDEX idx_bills_tenant_shipper ON bills (tenant_id, shipper);
CREATE INDEX idx_bills_tenant_shipper_normalized ON bills (tenant_id, shipper_normalized);
CREATE INDEX idx_bills_tenant_deleted ON bills (tenant_id, deleted_at);

CREATE TABLE bill_tags (
    bill_id BIGINT NOT NULL,
    tag VARCHAR(40) NOT NULL,
    PRIMARY KEY (bill_id, tag),
    CONSTRAINT fk_bill_tags_bill FOREIGN KEY (bill_id) REFERENCES bills (id) ON DELETE CASCADE
);

CREATE INDEX idx_bill_tags_tag ON bill_tags (tag);

CREATE TABLE audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NULL,
    action VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    request_id VARCHAR(64) NULL,
    details TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_audit_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id)
);

CREATE INDEX idx_audit_events_aggregate ON audit_events (tenant_id, aggregate_type, aggregate_id);
CREATE INDEX idx_audit_events_created_at ON audit_events (tenant_id, created_at);
