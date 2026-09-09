package com.demeter.backend.admin.application;

import static com.demeter.backend.admin.application.AdminCommandInputs.requireReason;

import com.demeter.backend.audit.application.AuditService;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.error.ConflictException;
import com.demeter.backend.common.error.ResourceNotFoundException;
import com.demeter.backend.common.idempotency.CanonicalValues;
import com.demeter.backend.common.idempotency.IdempotencyKeys;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.identity.domain.AuthSession;
import com.demeter.backend.identity.domain.Tenant;
import com.demeter.backend.identity.domain.TenantStatus;
import com.demeter.backend.identity.domain.UserAccount;
import com.demeter.backend.identity.infrastructure.AuthSessionRepository;
import com.demeter.backend.identity.infrastructure.TenantRepository;
import com.demeter.backend.identity.infrastructure.UserAccountRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminTenantCommandService {
    private static final String TENANT_SUSPEND = "admin.tenant.suspend";
    private static final String TENANT_ACTIVATE = "admin.tenant.activate";
    private static final String TENANT_REVOKE_SESSIONS = "admin.tenant.revoke-sessions";

    private final TenantRepository tenants;
    private final UserAccountRepository users;
    private final AuthSessionRepository authSessions;
    private final AuditService audit;
    private final Clock clock;
    private final AdminCommandRunner runner;
    private final BusinessChain<TenantStatusContext, Boolean> statusChain;
    private final BusinessChain<TenantSessionsContext, Boolean> sessionsChain;

    public AdminTenantCommandService(TenantRepository tenants, UserAccountRepository users,
            AuthSessionRepository authSessions, AuditService audit, Clock clock, AdminCommandRunner runner) {
        this.tenants = tenants;
        this.users = users;
        this.authSessions = authSessions;
        this.audit = audit;
        this.clock = clock;
        this.runner = runner;
        this.statusChain = buildStatusChain();
        this.sessionsChain = buildSessionsChain();
    }

    public void suspend(long id, String reason, String idempotencyKey) {
        runner.execute(statusChain, new TenantStatusContext(id, TenantStatus.SUSPENDED, reason, idempotencyKey));
    }

    public void activate(long id, String reason, String idempotencyKey) {
        runner.execute(statusChain, new TenantStatusContext(id, TenantStatus.ACTIVE, reason, idempotencyKey));
    }

    public void revokeSessions(long id, String reason, String idempotencyKey) {
        runner.execute(sessionsChain, new TenantSessionsContext(id, reason, idempotencyKey));
    }

    private BusinessChain<TenantStatusContext, Boolean> buildStatusChain() {
        return BusinessChain.of(
                "admin.tenant.status",
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("normalize-command", context -> {
                            context.reason = requireReason(context.requestedReason, "状态变更原因不能为空");
                            context.key = IdempotencyKeys.require(context.requestedKey);
                            context.hash = CanonicalValues.sha256(context.tenantId, context.reason);
                        }),
                        BusinessHandler.named("reserve-idempotency", context -> {
                            if (runner.reserve(context, clock.instant()) == AdminCommandRunner.Reservation.REPLAY) {
                                context.halt();
                            }
                        }),
                        BusinessHandler.named("load-tenant-for-update", context -> context.tenant = tenants
                                .findByIdForUpdate(context.tenantId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                        "Tenant " + context.tenantId + " does not exist"))),
                        BusinessHandler.named("validate-status-transition", context -> {
                            if (context.tenant.getStatus() == context.target) {
                                throw new ConflictException(
                                        "Tenant is already " + context.target.name().toLowerCase());
                            }
                        }),
                        BusinessHandler.named("change-status", context -> {
                            if (context.target == TenantStatus.ACTIVE) {
                                context.tenant.activate(clock.instant());
                            } else {
                                context.tenant.suspend(clock.instant());
                            }
                        }),
                        BusinessHandler.named("persist-tenant", context -> tenants.saveAndFlush(context.tenant)),
                        BusinessHandler.named("write-audit", context -> audit.recordSystem(
                                context.tenant.getId(),
                                context.target == TenantStatus.ACTIVE
                                        ? "ADMIN_TENANT_ACTIVATED" : "ADMIN_TENANT_SUSPENDED",
                                "TENANT",
                                context.tenant.getId(),
                                Map.of("reason", context.reason)))),
                ignored -> Boolean.TRUE);
    }

    private BusinessChain<TenantSessionsContext, Boolean> buildSessionsChain() {
        return BusinessChain.of(
                TENANT_REVOKE_SESSIONS,
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("normalize-command", context -> {
                            context.reason = requireReason(context.requestedReason, "撤销租户会话原因不能为空");
                            context.key = IdempotencyKeys.require(context.requestedKey);
                            context.hash = CanonicalValues.sha256(context.tenantId, context.reason);
                        }),
                        BusinessHandler.named("reserve-idempotency", context -> {
                            if (runner.reserve(context, clock.instant()) == AdminCommandRunner.Reservation.REPLAY) {
                                context.halt();
                            }
                        }),
                        BusinessHandler.named("load-tenant-for-update", context -> context.tenant = tenants
                                .findByIdForUpdate(context.tenantId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                        "Tenant " + context.tenantId + " does not exist"))),
                        BusinessHandler.named("load-users-for-update", context -> context.userIds = users
                                .findAllByTenantIdForUpdate(context.tenantId).stream()
                                .map(UserAccount::getId)
                                .toList()),
                        BusinessHandler.named("load-active-sessions-for-update", context -> context.sessions =
                                context.userIds.isEmpty()
                                        ? List.of()
                                        : authSessions.findActiveByUserIdsForUpdate(
                                                context.userIds, clock.instant())),
                        BusinessHandler.named("revoke-sessions", context -> {
                            var now = clock.instant();
                            context.sessions.forEach(session -> session.revoke(now));
                        }),
                        BusinessHandler.named("persist-sessions", context ->
                                authSessions.saveAllAndFlush(context.sessions)),
                        BusinessHandler.named("write-audit", context -> audit.recordSystem(
                                context.tenant.getId(),
                                "ADMIN_TENANT_SESSIONS_REVOKED",
                                "TENANT",
                                context.tenant.getId(),
                                Map.of(
                                        "reason", context.reason,
                                        "revokedCount", context.sessions.size(),
                                        "userCount", context.userIds.size())))),
                ignored -> Boolean.TRUE);
    }

    private static final class TenantStatusContext extends BusinessContext implements AdminIdempotentCommand {
        private final long tenantId;
        private final TenantStatus target;
        private final String requestedReason;
        private final String requestedKey;
        private String reason;
        private String key;
        private String hash;
        private Tenant tenant;

        private TenantStatusContext(long tenantId, TenantStatus target, String requestedReason, String requestedKey) {
            this.tenantId = tenantId;
            this.target = target;
            this.requestedReason = requestedReason;
            this.requestedKey = requestedKey;
        }

        @Override
        public String operationName() {
            return target == TenantStatus.ACTIVE ? TENANT_ACTIVATE : TENANT_SUSPEND;
        }

        @Override
        public String idempotencyKey() {
            return key;
        }

        @Override
        public String requestHash() {
            return hash;
        }
    }

    private static final class TenantSessionsContext extends BusinessContext implements AdminIdempotentCommand {
        private final long tenantId;
        private final String requestedReason;
        private final String requestedKey;
        private String reason;
        private String key;
        private String hash;
        private Tenant tenant;
        private List<Long> userIds;
        private List<AuthSession> sessions;

        private TenantSessionsContext(long tenantId, String requestedReason, String requestedKey) {
            this.tenantId = tenantId;
            this.requestedReason = requestedReason;
            this.requestedKey = requestedKey;
        }

        @Override
        public String operationName() {
            return TENANT_REVOKE_SESSIONS;
        }

        @Override
        public String idempotencyKey() {
            return key;
        }

        @Override
        public String requestHash() {
            return hash;
        }
    }
}
