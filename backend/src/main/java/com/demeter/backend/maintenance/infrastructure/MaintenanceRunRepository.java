package com.demeter.backend.maintenance.infrastructure;

import com.demeter.backend.maintenance.domain.MaintenanceRun;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceRunRepository extends JpaRepository<MaintenanceRun, Long> {

    @Query("""
            select run.id
            from MaintenanceRun run
            where run.startedAt < :cutoff
            order by run.startedAt, run.id
            """)
    List<Long> findIdsStartedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

    java.util.Optional<MaintenanceRun> findTopByRunTypeOrderByStartedAtDescIdDesc(String runType);

    List<MaintenanceRun> findAllByRunTypeOrderByStartedAtDescIdDesc(String runType, Pageable pageable);
}
