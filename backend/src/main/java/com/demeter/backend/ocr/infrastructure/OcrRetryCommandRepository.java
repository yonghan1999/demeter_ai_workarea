package com.demeter.backend.ocr.infrastructure;

import com.demeter.backend.ocr.domain.OcrRetryCommand;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OcrRetryCommandRepository extends JpaRepository<OcrRetryCommand, Long> {

    Optional<OcrRetryCommand> findByTenantIdAndIdempotencyKey(long tenantId, String idempotencyKey);

    @Query("select command.id from OcrRetryCommand command where command.createdAt < :cutoff order by command.createdAt, command.id")
    List<Long> findIdsCreatedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);
}
