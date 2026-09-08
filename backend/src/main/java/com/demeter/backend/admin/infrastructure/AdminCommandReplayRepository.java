package com.demeter.backend.admin.infrastructure;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminCommandReplayRepository extends JpaRepository<AdminCommandReplay, Long> {
    Optional<AdminCommandReplay> findByOperationNameAndIdempotencyKey(String operationName, String idempotencyKey);

    @Query("select replay.id from AdminCommandReplay replay where replay.createdAt < :cutoff order by replay.createdAt, replay.id")
    List<Long> findIdsCreatedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);
}
