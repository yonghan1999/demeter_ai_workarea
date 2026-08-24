package com.demeter.backend.audit.infrastructure;

import com.demeter.backend.audit.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
}
