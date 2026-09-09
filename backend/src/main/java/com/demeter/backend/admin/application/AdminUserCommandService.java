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
import com.demeter.backend.identity.domain.UserAccount;
import com.demeter.backend.identity.domain.UserStatus;
import com.demeter.backend.identity.infrastructure.AuthSessionRepository;
import com.demeter.backend.identity.infrastructure.UserAccountRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminUserCommandService {
    private static final String USER_DISABLE = "admin.user.disable";
    private static final String USER_ENABLE = "admin.user.enable";
    private static final String USER_REVOKE_SESSIONS = "admin.user.revoke-sessions";

    private final UserAccountRepository users;
    private final AuthSessionRepository authSessions;
    private final AuditService audit;
    private final Clock clock;
    private final AdminCommandRunner runner;
    private final BusinessChain<UserStatusContext, Boolean> statusChain;
    private final BusinessChain<UserSessionsContext, Boolean> sessionsChain;

    public AdminUserCommandService(UserAccountRepository users, AuthSessionRepository authSessions,
            AuditService audit, Clock clock, AdminCommandRunner runner) {
        this.users = users;
        this.authSessions = authSessions;
        this.audit = audit;
        this.clock = clock;
        this.runner = runner;
        this.statusChain = buildStatusChain();
        this.sessionsChain = buildSessionsChain();
    }

    public void disable(long id, String reason, String idempotencyKey) {
        runner.execute(statusChain, new UserStatusContext(id, UserStatus.DISABLED, reason, idempotencyKey));
    }

    public void enable(long id, String reason, String idempotencyKey) {
        runner.execute(statusChain, new UserStatusContext(id, UserStatus.ACTIVE, reason, idempotencyKey));
    }

    public void revokeSessions(long id, String reason, String idempotencyKey) {
        runner.execute(sessionsChain, new UserSessionsContext(id, reason, idempotencyKey));
    }

    private BusinessChain<UserStatusContext, Boolean> buildStatusChain() {
        return BusinessChain.of(
                "admin.user.status",
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("normalize-command", context -> {
                            context.reason = requireReason(context.requestedReason, "状态变更原因不能为空");
                            context.key = IdempotencyKeys.require(context.requestedKey);
                            context.hash = CanonicalValues.sha256(context.userId, context.reason);
                        }),
                        BusinessHandler.named("reserve-idempotency", context -> {
                            if (runner.reserve(context, clock.instant()) == AdminCommandRunner.Reservation.REPLAY) {
                                context.halt();
                            }
                        }),
                        BusinessHandler.named("load-user-for-update", context -> context.user = users
                                .findByIdForUpdate(context.userId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                        "User " + context.userId + " does not exist"))),
                        BusinessHandler.named("validate-status-transition", context -> {
                            if (context.user.getStatus() == context.target) {
                                throw new ConflictException(
                                        "User is already " + context.target.name().toLowerCase());
                            }
                        }),
                        BusinessHandler.named("change-status", context -> {
                            if (context.target == UserStatus.ACTIVE) {
                                context.user.enable(clock.instant());
                            } else {
                                context.user.disable(clock.instant());
                            }
                        }),
                        BusinessHandler.named("persist-user", context -> users.saveAndFlush(context.user)),
                        BusinessHandler.named("write-audit", context -> audit.recordSystem(
                                context.user.getTenantId(),
                                context.target == UserStatus.ACTIVE
                                        ? "ADMIN_USER_ENABLED" : "ADMIN_USER_DISABLED",
                                "USER",
                                context.user.getId(),
                                Map.of("reason", context.reason)))),
                ignored -> Boolean.TRUE);
    }

    private BusinessChain<UserSessionsContext, Boolean> buildSessionsChain() {
        return BusinessChain.of(
                USER_REVOKE_SESSIONS,
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("normalize-command", context -> {
                            context.reason = requireReason(context.requestedReason, "撤销会话原因不能为空");
                            context.key = IdempotencyKeys.require(context.requestedKey);
                            context.hash = CanonicalValues.sha256(context.userId, context.reason);
                        }),
                        BusinessHandler.named("reserve-idempotency", context -> {
                            if (runner.reserve(context, clock.instant()) == AdminCommandRunner.Reservation.REPLAY) {
                                context.halt();
                            }
                        }),
                        BusinessHandler.named("load-user-for-update", context -> context.user = users
                                .findByIdForUpdate(context.userId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                        "User " + context.userId + " does not exist"))),
                        BusinessHandler.named("load-active-sessions-for-update", context -> context.sessions =
                                authSessions.findActiveByUserIdForUpdate(context.userId, clock.instant())),
                        BusinessHandler.named("revoke-sessions", context -> {
                            var now = clock.instant();
                            context.sessions.forEach(session -> session.revoke(now));
                        }),
                        BusinessHandler.named("persist-sessions", context ->
                                authSessions.saveAllAndFlush(context.sessions)),
                        BusinessHandler.named("write-audit", context -> audit.recordSystem(
                                context.user.getTenantId(),
                                "ADMIN_USER_SESSIONS_REVOKED",
                                "USER",
                                context.user.getId(),
                                Map.of(
                                        "reason", context.reason,
                                        "revokedCount", context.sessions.size())))),
                ignored -> Boolean.TRUE);
    }

    private static final class UserStatusContext extends BusinessContext implements AdminIdempotentCommand {
        private final long userId;
        private final UserStatus target;
        private final String requestedReason;
        private final String requestedKey;
        private String reason;
        private String key;
        private String hash;
        private UserAccount user;

        private UserStatusContext(long userId, UserStatus target, String requestedReason, String requestedKey) {
            this.userId = userId;
            this.target = target;
            this.requestedReason = requestedReason;
            this.requestedKey = requestedKey;
        }

        @Override
        public String operationName() {
            return target == UserStatus.ACTIVE ? USER_ENABLE : USER_DISABLE;
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

    private static final class UserSessionsContext extends BusinessContext implements AdminIdempotentCommand {
        private final long userId;
        private final String requestedReason;
        private final String requestedKey;
        private String reason;
        private String key;
        private String hash;
        private UserAccount user;
        private List<AuthSession> sessions;

        private UserSessionsContext(long userId, String requestedReason, String requestedKey) {
            this.userId = userId;
            this.requestedReason = requestedReason;
            this.requestedKey = requestedKey;
        }

        @Override
        public String operationName() {
            return USER_REVOKE_SESSIONS;
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
