package com.demeter.backend.identity.infrastructure;

import com.demeter.backend.identity.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Page<Tenant> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
