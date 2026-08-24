package com.demeter.backend.identity.infrastructure;

import com.demeter.backend.identity.domain.AuthSession;
import com.demeter.backend.security.DemeterPrincipal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.List;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {

    @Query("""
            select session
            from AuthSession session
            where session.tokenHash = :tokenHash
              and session.revokedAt is null
              and session.expiresAt > :now
            """)
    Optional<AuthSession> findActiveByTokenHash(
            @Param("tokenHash") String tokenHash,
            @Param("now") Instant now);

    Optional<AuthSession> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthSession session where session.tokenHash = :tokenHash")
    Optional<AuthSession> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Query("""
            select session
            from AuthSession session
            where session.userId = :userId
              and session.revokedAt is null
              and session.expiresAt > :now
            order by session.createdAt desc, session.id desc
            """)
    List<AuthSession> findActiveByUserId(
            @Param("userId") long userId,
            @Param("now") Instant now);

    /**
     * Locks the active session rows while a login is persisted. This serializes
     * the max-active-session calculation for concurrent logins of one user.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select session
            from AuthSession session
            where session.userId = :userId
              and session.revokedAt is null
              and session.expiresAt > :now
            order by session.createdAt desc, session.id desc
            """)
    List<AuthSession> findActiveByUserIdForUpdate(
            @Param("userId") long userId,
            @Param("now") Instant now);

    @Query("""
            select new com.demeter.backend.security.DemeterPrincipal(
                user.id, user.tenantId, user.openId, user.displayName)
            from AuthSession session, UserAccount user, Tenant tenant
            where session.userId = user.id
              and user.tenantId = tenant.id
              and session.tokenHash = :tokenHash
              and session.revokedAt is null
              and session.expiresAt > :now
              and user.status = com.demeter.backend.identity.domain.UserStatus.ACTIVE
              and tenant.status = com.demeter.backend.identity.domain.TenantStatus.ACTIVE
            """)
    Optional<DemeterPrincipal> findAuthenticatedPrincipal(
            @Param("tokenHash") String tokenHash,
            @Param("now") Instant now);

    @Modifying
    @Query("""
            delete from AuthSession session
            where session.expiresAt < :cutoff
               or (session.revokedAt is not null and session.revokedAt < :cutoff)
            """)
    int deleteInactiveBefore(@Param("cutoff") Instant cutoff);

    @Query("""
            select session.id
            from AuthSession session
            where session.expiresAt < :cutoff
               or (session.revokedAt is not null and session.revokedAt < :cutoff)
            order by session.expiresAt, session.id
            """)
    List<String> findInactiveIdsBefore(@Param("cutoff") Instant cutoff, Pageable pageable);
}
