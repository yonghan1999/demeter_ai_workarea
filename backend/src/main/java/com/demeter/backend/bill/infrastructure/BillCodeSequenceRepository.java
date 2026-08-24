package com.demeter.backend.bill.infrastructure;

import com.demeter.backend.bill.domain.BillCodeSequence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillCodeSequenceRepository extends JpaRepository<BillCodeSequence, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select item
            from BillCodeSequence item
            where item.tenantId = :tenantId and item.name = :name
            """)
    Optional<BillCodeSequence> findByTenantAndNameForUpdate(
            @Param("tenantId") long tenantId,
            @Param("name") String name);
}
