package com.demeter.backend.audit.infrastructure;

import com.demeter.backend.audit.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
    Page<AuditEvent> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
