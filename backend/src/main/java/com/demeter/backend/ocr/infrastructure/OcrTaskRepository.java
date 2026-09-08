package com.demeter.backend.ocr.infrastructure;

import com.demeter.backend.ocr.domain.OcrTask;
import com.demeter.backend.ocr.domain.OcrTaskStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OcrTaskRepository extends JpaRepository<OcrTask, Long>, JpaSpecificationExecutor<OcrTask> {

    Optional<OcrTask> findByPublicIdAndTenantId(String publicId, long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from OcrTask task where task.publicId = :publicId")
    Optional<OcrTask> findByPublicIdForUpdate(@Param("publicId") String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select task
            from OcrTask task
            where task.publicId = :publicId
              and task.tenantId = :tenantId
            """)
    Optional<OcrTask> findByPublicIdAndTenantIdForUpdate(
            @Param("publicId") String publicId,
            @Param("tenantId") long tenantId);

    Optional<OcrTask> findByTenantIdAndIdempotencyKey(long tenantId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from OcrTask task where task.id = :id")
    Optional<OcrTask> findByIdForUpdate(@Param("id") long id);

    Page<OcrTask> findAllByTenantId(long tenantId, Pageable pageable);

    Page<OcrTask> findAllByTenantIdAndStatus(long tenantId, OcrTaskStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select task
            from OcrTask task
            where task.status in :terminalStatuses
              and task.completedAt < :completedBefore
              and task.storageDeletedAt is null
            order by task.completedAt asc
            """)
    List<OcrTask> findStorageCleanupCandidates(
            @Param("terminalStatuses") Collection<OcrTaskStatus> terminalStatuses,
            @Param("completedBefore") Instant completedBefore,
            Pageable pageable);

    @Query("select task.storageKey from OcrTask task where task.storageKey in :keys")
    Set<String> findReferencedStorageKeys(@Param("keys") Collection<String> keys);

    @Query(value = """
            SELECT *
            FROM ocr_tasks
            WHERE ((status IN ('PENDING', 'RETRYING') AND next_attempt_at <= :now)
                OR (status = 'PROCESSING' AND lease_until <= :now))
            ORDER BY created_at ASC, id ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OcrTask> findNextClaimableForUpdate(@Param("now") Instant now);

    @Query("""
            select new com.demeter.backend.ocr.infrastructure.OcrTaskStatusCount(
                task.status, count(task))
            from OcrTask task
            group by task.status
            """)
    List<OcrTaskStatusCount> countTasksByStatus();

    @Query("""
            select min(task.createdAt)
            from OcrTask task
            where ((task.status in :readyStatuses and task.nextAttemptAt <= :now)
                or (task.status = :processingStatus and task.leaseUntil <= :now))
            """)
    Optional<Instant> findOldestClaimableCreatedAt(
            @Param("readyStatuses") Collection<OcrTaskStatus> readyStatuses,
            @Param("processingStatus") OcrTaskStatus processingStatus,
            @Param("now") Instant now);
}
