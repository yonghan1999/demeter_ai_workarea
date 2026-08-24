package com.demeter.backend.identity.infrastructure;

import com.demeter.backend.identity.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
}
