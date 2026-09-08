package com.demeter.backend.admin.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminSessionRepository extends JpaRepository<AdminSession, String> {
    @Query("select session from AdminSession session where session.tokenHash = :tokenHash and session.revokedAt is null and session.expiresAt > :now")
    Optional<AdminSession> findActive(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    @Query("select session from AdminSession session where session.tokenHash = :tokenHash")
    Optional<AdminSession> findByTokenHash(@Param("tokenHash") String tokenHash);

    @Query("select session.id from AdminSession session where session.expiresAt < :cutoff or (session.revokedAt is not null and session.revokedAt < :cutoff) order by session.expiresAt, session.id")
    List<String> findInactiveIdsBefore(@Param("cutoff") Instant cutoff, Pageable pageable);
}
