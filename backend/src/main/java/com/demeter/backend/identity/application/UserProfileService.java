package com.demeter.backend.identity.application;

import com.demeter.backend.audit.application.AuditService;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.error.BusinessRuleException;
import com.demeter.backend.common.error.UnauthorizedException;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.identity.api.UpdateNicknameRequest;
import com.demeter.backend.identity.api.UserProfileResponse;
import com.demeter.backend.identity.domain.Tenant;
import com.demeter.backend.identity.domain.TenantStatus;
import com.demeter.backend.identity.domain.UserAccount;
import com.demeter.backend.identity.domain.UserStatus;
import com.demeter.backend.identity.infrastructure.TenantRepository;
import com.demeter.backend.identity.infrastructure.UserAccountRepository;
import com.demeter.backend.security.CurrentActor;
import com.demeter.backend.security.DemeterPrincipal;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class UserProfileService {

    private final UserAccountRepository users;
    private final TenantRepository tenants;
    private final CurrentActor currentActor;
    private final AuditService audit;
    private final BusinessChainExecutor executor;
    private final Clock clock;
    private final BusinessChain<ProfileContext, UserProfileResponse> readChain;
    private final BusinessChain<ProfileContext, UserProfileResponse> updateChain;

    public UserProfileService(UserAccountRepository users, TenantRepository tenants, CurrentActor currentActor,
            AuditService audit, BusinessChainExecutor executor, Clock clock) {
        this.users = users;
        this.tenants = tenants;
        this.currentActor = currentActor;
        this.audit = audit;
        this.executor = executor;
        this.clock = clock;
        this.readChain = BusinessChain.of("identity.read-profile", BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("resolve-actor", context -> context.actor = currentActor.require()),
                        BusinessHandler.named("load-profile", context -> loadProfile(context, false)),
                        BusinessHandler.named("map-response", this::mapResponse)),
                context -> context.result);
        this.updateChain = BusinessChain.of("identity.update-nickname", BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("resolve-actor", context -> context.actor = currentActor.require()),
                        BusinessHandler.named("validate-nickname", context -> {
                            String name = context.request.displayName();
                            if (name == null || name.isBlank() || name.length() > 120) {
                                throw new BusinessRuleException("昵称不能为空且不能超过 120 字符");
                            }
                            context.nickname = name.trim();
                        }),
                        BusinessHandler.named("load-profile-for-update", context -> loadProfile(context, true)),
                        BusinessHandler.named("persist-nickname", context -> {
                            context.changed = !context.nickname.equals(context.user.getDisplayName());
                            context.user.updateDisplayName(context.nickname, clock.instant());
                            users.save(context.user);
                        }),
                        BusinessHandler.named("audit-nickname", context -> {
                            if (context.changed) {
                                audit.record(context.actor, "USER_NICKNAME_UPDATED", "USER", context.user.getId(), null);
                            }
                        }),
                        BusinessHandler.named("map-response", this::mapResponse)),
                context -> context.result);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse get() {
        return executor.execute(readChain, new ProfileContext(null));
    }

    @Transactional
    public UserProfileResponse updateNickname(UpdateNicknameRequest request) {
        return executor.execute(updateChain, new ProfileContext(request));
    }

    private void loadProfile(ProfileContext context, boolean forUpdate) {
        context.user = (forUpdate
                ? users.findByIdAndTenantIdForUpdate(context.actor.userId(), context.actor.tenantId())
                : users.findByIdAndTenantId(context.actor.userId(), context.actor.tenantId()))
                .orElseThrow(() -> new UnauthorizedException("The account is not available"));
        context.tenant = tenants.findById(context.actor.tenantId())
                .orElseThrow(() -> new UnauthorizedException("The account is not available"));
        if (context.user.getStatus() != UserStatus.ACTIVE || context.tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new UnauthorizedException("The account is not available");
        }
    }

    private void mapResponse(ProfileContext context) {
        context.result = new UserProfileResponse(
                context.user.getId(), context.tenant.getPublicId(), context.user.getDisplayName());
    }

    private static final class ProfileContext extends BusinessContext {
        private final UpdateNicknameRequest request;
        private DemeterPrincipal actor;
        private UserAccount user;
        private Tenant tenant;
        private String nickname;
        private boolean changed;
        private UserProfileResponse result;

        private ProfileContext(UpdateNicknameRequest request) {
            this.request = request;
        }
    }
}
