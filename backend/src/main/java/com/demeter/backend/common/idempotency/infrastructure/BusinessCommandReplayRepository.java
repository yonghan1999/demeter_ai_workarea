package com.demeter.backend.common.idempotency.infrastructure;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BusinessCommandReplayRepository extends JpaRepository<BusinessCommandReplay, Long> {

    Optional<BusinessCommandReplay> findByTenantIdAndOperationNameAndIdempotencyKey(
            long tenantId,
            String operationName,
            String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select replay
            from BusinessCommandReplay replay
            where replay.tenantId = :tenantId
              and replay.operationName = :operationName
              and replay.idempotencyKey = :idempotencyKey
            """)
    Optional<BusinessCommandReplay> findForUpdate(
            @Param("tenantId") long tenantId,
            @Param("operationName") String operationName,
            @Param("idempotencyKey") String idempotencyKey);

    @Query("""
            select replay.id
            from BusinessCommandReplay replay
            where replay.createdAt < :cutoff
            order by replay.createdAt, replay.id
            """)
    List<Long> findIdsCreatedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);
}
