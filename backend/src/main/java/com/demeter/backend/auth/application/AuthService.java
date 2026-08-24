package com.demeter.backend.auth.application;

import com.demeter.backend.auth.api.LoginResponse;
import com.demeter.backend.auth.api.LogoutResponse;
import com.demeter.backend.auth.api.WechatLoginRequest;
import com.demeter.backend.auth.spi.WechatCodeExchangeClient;
import com.demeter.backend.auth.spi.WechatIdentity;
import com.demeter.backend.audit.application.AuditService;
import com.demeter.backend.bill.domain.BillCodeSequence;
import com.demeter.backend.bill.infrastructure.BillCodeSequenceRepository;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.error.UnauthorizedException;
import com.demeter.backend.identity.domain.AuthSession;
import com.demeter.backend.identity.domain.Tenant;
import com.demeter.backend.identity.domain.TenantStatus;
import com.demeter.backend.identity.domain.UserAccount;
import com.demeter.backend.identity.domain.UserStatus;
import com.demeter.backend.identity.infrastructure.AuthSessionRepository;
import com.demeter.backend.identity.infrastructure.TenantRepository;
import com.demeter.backend.identity.infrastructure.UserAccountRepository;
import com.demeter.backend.security.CurrentActor;
import com.demeter.backend.security.DemeterPrincipal;
import com.demeter.backend.security.TokenDigests;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AuthService {

    private static final String BILL_SEQUENCE = "bill";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WechatCodeExchangeClient wechatClient;
    private final TenantRepository tenantRepository;
    private final UserAccountRepository userRepository;
    private final AuthSessionRepository sessionRepository;
    private final BillCodeSequenceRepository sequenceRepository;
    private final CurrentActor currentActor;
    private final AuditService auditService;
    private final BusinessChainExecutor chainExecutor;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final AuthProperties properties;
    private final BusinessChain<LoginContext, LoginResponse> loginChain;
    private final BusinessChain<LogoutContext, LogoutResponse> logoutChain;

    public AuthService(
            WechatCodeExchangeClient wechatClient,
            TenantRepository tenantRepository,
            UserAccountRepository userRepository,
            AuthSessionRepository sessionRepository,
            BillCodeSequenceRepository sequenceRepository,
            CurrentActor currentActor,
            AuditService auditService,
            BusinessChainExecutor chainExecutor,
            PlatformTransactionManager transactionManager,
            Clock clock,
            AuthProperties properties) {
        this.wechatClient = wechatClient;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.sequenceRepository = sequenceRepository;
        this.currentActor = currentActor;
        this.auditService = auditService;
        this.chainExecutor = chainExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
        this.loginChain = buildLoginChain();
        this.logoutChain = buildLogoutChain();
    }

    public LoginResponse login(WechatLoginRequest request) {
        return chainExecutor.execute(loginChain, new LoginContext(request));
    }

    public LogoutResponse logout(String rawToken) {
        return chainExecutor.execute(logoutChain, new LogoutContext(rawToken));
    }

    private BusinessChain<LoginContext, LoginResponse> buildLoginChain() {
        return BusinessChain.of(
                "auth.wechat-login",
                BusinessChainExecutionMode.EXTERNAL_IO,
                List.of(
                        BusinessHandler.named("normalize-request", context -> {
                            context.code = context.request.code().trim();
                            context.displayName = normalizeDisplayName(context.request.displayName());
                        }),
                        BusinessHandler.named("exchange-wechat-code", context ->
                                context.identity = wechatClient.exchange(context.code)),
                        BusinessHandler.named("generate-session-token", context -> {
                            context.rawToken = newToken();
                            context.tokenHash = TokenDigests.sha256(context.rawToken);
                            context.expiresAt = clock.instant().plus(properties.sessionTtl());
                        }),
                        BusinessHandler.named("persist-login", this::persistLoginWithConflictRecovery),
                        BusinessHandler.named("map-response", context -> context.result = new LoginResponse(
                                context.rawToken,
                                "Bearer",
                                context.expiresAt,
                                new LoginResponse.UserView(
                                        context.user.getId(),
                                        context.tenant.getPublicId(),
                                        context.user.getDisplayName())))),
                context -> context.result);
    }

    private BusinessChain<LogoutContext, LogoutResponse> buildLogoutChain() {
        return BusinessChain.of(
                "auth.logout",
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("validate-token", context -> {
                            if (context.rawToken == null || context.rawToken.isBlank()) {
                                throw new UnauthorizedException("Bearer token is required");
                            }
                            context.tokenHash = TokenDigests.sha256(context.rawToken);
                        }),
                        BusinessHandler.named("revoke-session", context -> transactionTemplate.executeWithoutResult(status -> {
                            AuthSession session = sessionRepository
                                    .findByTokenHashForUpdate(context.tokenHash)
                                    .orElse(null);
                            if (session == null || session.getRevokedAt() != null) {
                                return;
                            }
                            session.revoke(clock.instant());
                            sessionRepository.save(session);
                            context.actor = currentActor.optional().orElse(null);
                            if (context.actor == null) {
                                return;
                            }
                            auditService.record(context.actor, "AUTH_LOGOUT", "USER", context.actor.userId(), null);
                        })),
                        BusinessHandler.named("map-response", context -> context.result = new LogoutResponse(true))),
                context -> context.result);
    }

    private void persistLogin(LoginContext context) {
        Instant now = clock.instant();
        UserAccount existing = userRepository.findByOpenId(context.identity.openId()).orElse(null);
        if (existing == null) {
            Tenant tenant = tenantRepository.save(new Tenant(context.displayName + "的账本", now));
            UserAccount user = userRepository.save(new UserAccount(
                    tenant.getId(),
                    context.identity.openId(),
                    context.identity.unionId(),
                    context.displayName,
                    now));
            sequenceRepository.save(new BillCodeSequence(tenant.getId(), BILL_SEQUENCE, 100));
            context.tenant = tenant;
            context.user = user;
        } else {
            context.user = userRepository.findByIdForUpdate(existing.getId())
                    .orElseThrow(() -> new IllegalStateException("User disappeared during login"));
            context.tenant = tenantRepository.findById(existing.getTenantId())
                    .orElseThrow(() -> new IllegalStateException("User tenant is missing"));
        }
        validateLoginAllowed(context.user, context.tenant);
        persistSessionAndAudit(context, existing == null, now);
    }

    private void persistLoginWithConflictRecovery(LoginContext context) {
        try {
            transactionTemplate.executeWithoutResult(status -> persistLogin(context));
        } catch (DataIntegrityViolationException conflict) {
            transactionTemplate.executeWithoutResult(status -> {
                UserAccount user = userRepository.findByOpenId(context.identity.openId())
                        .orElseThrow(() -> conflict);
                user = userRepository.findByIdForUpdate(user.getId())
                        .orElseThrow(() -> new IllegalStateException("User disappeared during login recovery"));
                Tenant tenant = tenantRepository.findById(user.getTenantId())
                        .orElseThrow(() -> new IllegalStateException("User tenant is missing"));
                context.user = user;
                context.tenant = tenant;
                validateLoginAllowed(user, tenant);
                persistSessionAndAudit(context, false, clock.instant());
            });
        }
    }

    private void validateLoginAllowed(UserAccount user, Tenant tenant) {
        if (user.getStatus() != UserStatus.ACTIVE || tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new UnauthorizedException("The account is not available");
        }
    }

    private void persistSessionAndAudit(LoginContext context, boolean newUser, Instant now) {
        sessionRepository.save(new AuthSession(
                context.user.getId(), context.tokenHash, context.expiresAt, now));
        List<AuthSession> activeSessions = sessionRepository.findActiveByUserIdForUpdate(
                context.user.getId(), now);
        activeSessions.stream()
                .skip(properties.maxActiveSessions())
                .forEach(session -> session.revoke(now));
        sessionRepository.saveAll(activeSessions);
        auditService.record(
                new DemeterPrincipal(
                        context.user.getId(),
                        context.tenant.getId(),
                        context.user.getOpenId(),
                        context.user.getDisplayName()),
                "AUTH_LOGIN",
                "USER",
                context.user.getId(),
                Map.of("newUser", newUser));
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "微信用户";
        }
        return displayName.trim();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class LoginContext extends BusinessContext {
        private final WechatLoginRequest request;
        private String code;
        private String displayName;
        private WechatIdentity identity;
        private String rawToken;
        private String tokenHash;
        private Instant expiresAt;
        private Tenant tenant;
        private UserAccount user;
        private LoginResponse result;

        private LoginContext(WechatLoginRequest request) {
            this.request = request;
        }
    }

    private static final class LogoutContext extends BusinessContext {
        private final String rawToken;
        private String tokenHash;
        private DemeterPrincipal actor;
        private LogoutResponse result;

        private LogoutContext(String rawToken) {
            this.rawToken = rawToken;
        }
    }
}
