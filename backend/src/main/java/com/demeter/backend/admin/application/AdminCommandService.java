package com.demeter.backend.admin.application;

import com.demeter.backend.audit.application.AuditService;
import com.demeter.backend.bill.domain.Bill;
import com.demeter.backend.bill.infrastructure.BillRepository;
import com.demeter.backend.common.error.BusinessRuleException;
import com.demeter.backend.common.error.ConflictException;
import com.demeter.backend.common.error.ResourceNotFoundException;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.idempotency.CanonicalValues;
import com.demeter.backend.common.idempotency.IdempotencyKeys;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.identity.domain.Tenant;
import com.demeter.backend.identity.domain.TenantStatus;
import com.demeter.backend.identity.domain.UserAccount;
import com.demeter.backend.identity.domain.UserStatus;
import com.demeter.backend.identity.infrastructure.TenantRepository;
import com.demeter.backend.identity.infrastructure.UserAccountRepository;
import com.demeter.backend.identity.infrastructure.AuthSessionRepository;
import com.demeter.backend.ocr.domain.OcrTask;
import com.demeter.backend.ocr.infrastructure.OcrTaskRepository;
import com.demeter.backend.ocr.spi.HandwrittenBillOcrProvider;
import com.demeter.backend.payment.domain.Payment;
import com.demeter.backend.payment.infrastructure.PaymentRepository;
import com.demeter.backend.admin.infrastructure.AdminCommandReplay;
import com.demeter.backend.admin.infrastructure.AdminCommandReplayRepository;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminCommandService {
    private static final String BILL_DELETE = "admin.bill.delete";
    private static final String BILL_RESTORE = "admin.bill.restore";
    private static final String OCR_RETRY = "admin.ocr.retry";
    private static final String TENANT_SUSPEND = "admin.tenant.suspend";
    private static final String TENANT_ACTIVATE = "admin.tenant.activate";
    private static final String USER_DISABLE = "admin.user.disable";
    private static final String USER_ENABLE = "admin.user.enable";
    private static final String USER_REVOKE_SESSIONS = "admin.user.revoke-sessions";
    private static final String PAYMENT_REVERSE = "admin.payment.reverse";

    private final TenantRepository tenants;
    private final UserAccountRepository users;
    private final AuthSessionRepository authSessions;
    private final BillRepository bills;
    private final OcrTaskRepository ocrTasks;
    private final PaymentRepository payments;
    private final AdminCommandReplayRepository replays;
    private final AuditService audit;
    private final ObjectProvider<HandwrittenBillOcrProvider> ocrProvider;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final BusinessChainExecutor executor;
    private final BusinessChain<CommandContext, Boolean> commandChain;

    public AdminCommandService(
            TenantRepository tenants,
            UserAccountRepository users,
            AuthSessionRepository authSessions,
            BillRepository bills,
            OcrTaskRepository ocrTasks,
            PaymentRepository payments,
            AdminCommandReplayRepository replays,
            AuditService audit,
            ObjectProvider<HandwrittenBillOcrProvider> ocrProvider,
            PlatformTransactionManager transactionManager,
            Clock clock,
            BusinessChainExecutor executor) {
        this.tenants = tenants;
        this.users = users;
        this.authSessions = authSessions;
        this.bills = bills;
        this.ocrTasks = ocrTasks;
        this.payments = payments;
        this.replays = replays;
        this.audit = audit;
        this.ocrProvider = ocrProvider;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.executor = executor;
        this.commandChain = BusinessChain.of(
                "admin.command", BusinessChainExecutionMode.ATOMIC_DATABASE,
                java.util.List.of(BusinessHandler.named("execute", context -> {
                    context.command.run();
                    context.result = true;
                })), context -> context.result);
    }

    public void deleteBill(long id, String reason, String idempotencyKey) {
        String normalizedReason = requireReason(reason, "删除原因不能为空");
        execute(BILL_DELETE, idempotencyKey, CanonicalValues.sha256(id, normalizedReason), () -> {
            Bill bill = requireBill(id);
            if (bill.getDeletedAt() != null) {
                throw new ConflictException("The bill is already deleted");
            }
            bill.softDeleteBySystem(normalizedReason, clock.instant());
            bills.saveAndFlush(bill);
            audit.recordSystem(bill.getTenantId(), "ADMIN_BILL_DELETED", "BILL", bill.getId(),
                    Map.of("reason", normalizedReason));
        });
    }

    public void restoreBill(long id, String reason, String idempotencyKey) {
        String normalizedReason = requireReason(reason, "恢复原因不能为空");
        execute(BILL_RESTORE, idempotencyKey, CanonicalValues.sha256(id, normalizedReason), () -> {
            Bill bill = requireBill(id);
            if (bill.getDeletedAt() == null) {
                throw new ConflictException("The bill is not deleted");
            }
            bill.restoreBySystem(clock.instant());
            bills.saveAndFlush(bill);
            audit.recordSystem(bill.getTenantId(), "ADMIN_BILL_RESTORED", "BILL", bill.getId(),
                    Map.of("reason", normalizedReason));
        });
    }

    public void retryOcr(String publicId, String idempotencyKey) {
        String key = IdempotencyKeys.require(idempotencyKey);
        execute(OCR_RETRY, key, CanonicalValues.sha256(publicId), () -> {
            if (ocrProvider.getIfUnique() == null) {
                throw new BusinessRuleException("OCR recognition provider is not configured");
            }
            OcrTask task = ocrTasks.findByPublicIdForUpdate(publicId)
                    .orElseThrow(() -> new ResourceNotFoundException("OCR task does not exist"));
            try {
                task.retry(clock.instant());
            } catch (IllegalStateException exception) {
                throw new BusinessRuleException(exception.getMessage());
            }
            ocrTasks.saveAndFlush(task);
            audit.recordSystem(task.getTenantId(), "ADMIN_OCR_TASK_RETRIED", "OCR_TASK", task.getPublicId(), null);
        });
    }

    public void suspendTenant(long id, String reason, String idempotencyKey) {
        changeTenantStatus(id, TenantStatus.SUSPENDED, reason, idempotencyKey);
    }

    public void activateTenant(long id, String reason, String idempotencyKey) {
        changeTenantStatus(id, TenantStatus.ACTIVE, reason, idempotencyKey);
    }

    public void disableUser(long id, String reason, String idempotencyKey) {
        changeUserStatus(id, UserStatus.DISABLED, reason, idempotencyKey);
    }

    public void enableUser(long id, String reason, String idempotencyKey) {
        changeUserStatus(id, UserStatus.ACTIVE, reason, idempotencyKey);
    }

    public void revokeUserSessions(long id, String reason, String idempotencyKey) {
        String normalizedReason = requireReason(reason, "撤销会话原因不能为空");
        execute(USER_REVOKE_SESSIONS, idempotencyKey, CanonicalValues.sha256(id, normalizedReason), () -> {
            UserAccount user = users.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("User " + id + " does not exist"));
            var now = clock.instant();
            var activeSessions = authSessions.findActiveByUserIdForUpdate(id, now);
            activeSessions.forEach(session -> session.revoke(now));
            authSessions.saveAllAndFlush(activeSessions);
            audit.recordSystem(user.getTenantId(), "ADMIN_USER_SESSIONS_REVOKED", "USER", user.getId(),
                    Map.of("reason", normalizedReason, "revokedCount", activeSessions.size()));
        });
    }

    public void reversePayment(long billId, long paymentId, String reason, String idempotencyKey) {
        String normalizedReason = requireReason(reason, "冲正原因不能为空");
        String key = IdempotencyKeys.require(idempotencyKey);
        String requestHash = CanonicalValues.sha256(billId, paymentId, normalizedReason);
        execute(PAYMENT_REVERSE, key, requestHash, () -> {
            Bill bill = bills.findByIdForUpdate(billId)
                    .orElseThrow(() -> new ResourceNotFoundException("Bill " + billId + " does not exist"));
            Payment payment = payments.findByIdAndTenantIdForUpdate(paymentId, bill.getTenantId())
                    .orElseThrow(() -> new ResourceNotFoundException("Payment " + paymentId + " does not exist"));
            if (!payment.getBillId().equals(billId)) {
                throw new ResourceNotFoundException("Payment does not belong to the bill");
            }
            if (payment.getStatus() != com.demeter.backend.payment.domain.PaymentStatus.ACTIVE) {
                throw new ConflictException("The payment has already been reversed");
            }
            BigDecimal ledgerAmount = payments.sumActiveAmount(
                    bill.getTenantId(), bill.getId(), BigDecimal.ZERO.setScale(2));
            if (ledgerAmount.compareTo(bill.getPaidAmount()) != 0) {
                throw new ConflictException("The bill payment ledger is inconsistent; contact support before retrying");
            }
            try {
                payment.reverse(null, normalizedReason, key, requestHash, clock.instant());
                bill.reversePaymentBySystem(payment.getAmount(), clock.instant());
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw new BusinessRuleException(exception.getMessage());
            }
            payments.saveAndFlush(payment);
            bills.saveAndFlush(bill);
            audit.recordSystem(bill.getTenantId(), "ADMIN_PAYMENT_REVERSED", "PAYMENT", payment.getId(),
                    Map.of("billId", billId, "reason", normalizedReason));
        });
    }

    private void changeTenantStatus(long id, TenantStatus target, String reason, String idempotencyKey) {
        String normalizedReason = requireReason(reason, "状态变更原因不能为空");
        String operation = target == TenantStatus.ACTIVE ? TENANT_ACTIVATE : TENANT_SUSPEND;
        execute(operation, idempotencyKey, CanonicalValues.sha256(id, normalizedReason), () -> {
            Tenant tenant = tenants.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Tenant " + id + " does not exist"));
            if (tenant.getStatus() == target) throw new ConflictException("Tenant is already " + target.name().toLowerCase());
            if (target == TenantStatus.ACTIVE) tenant.activate(clock.instant());
            else tenant.suspend(clock.instant());
            tenants.saveAndFlush(tenant);
            audit.recordSystem(tenant.getId(), target == TenantStatus.ACTIVE
                            ? "ADMIN_TENANT_ACTIVATED" : "ADMIN_TENANT_SUSPENDED",
                    "TENANT", tenant.getId(), Map.of("reason", normalizedReason));
        });
    }

    private void changeUserStatus(long id, UserStatus target, String reason, String idempotencyKey) {
        String normalizedReason = requireReason(reason, "状态变更原因不能为空");
        String operation = target == UserStatus.ACTIVE ? USER_ENABLE : USER_DISABLE;
        execute(operation, idempotencyKey, CanonicalValues.sha256(id, normalizedReason), () -> {
            UserAccount user = users.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("User " + id + " does not exist"));
            if (user.getStatus() == target) throw new ConflictException("User is already " + target.name().toLowerCase());
            if (target == UserStatus.ACTIVE) user.enable(clock.instant());
            else user.disable(clock.instant());
            users.saveAndFlush(user);
            audit.recordSystem(user.getTenantId(), target == UserStatus.ACTIVE
                            ? "ADMIN_USER_ENABLED" : "ADMIN_USER_DISABLED",
                    "USER", user.getId(), Map.of("reason", normalizedReason));
        });
    }

    private Bill requireBill(long id) {
        return bills.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bill " + id + " does not exist"));
    }

    private void execute(String operation, String idempotencyKey, String requestHash, Runnable command) {
        String key = IdempotencyKeys.require(idempotencyKey);
        executor.execute(commandChain, new CommandContext(() -> executeTransactional(operation, key, requestHash, command)));
    }

    private void executeTransactional(String operation, String key, String requestHash, Runnable command) {
        try {
            transaction.executeWithoutResult(status -> {
                Optional<AdminCommandReplay> existing = replays.findByOperationNameAndIdempotencyKey(operation, key);
                if (existing.isPresent()) {
                    verifyReplay(existing.get(), requestHash);
                    return;
                }
                replays.saveAndFlush(new AdminCommandReplay(operation, key, requestHash, clock.instant()));
                command.run();
            });
        } catch (DataIntegrityViolationException race) {
            transaction.executeWithoutResult(status -> replays.findByOperationNameAndIdempotencyKey(operation, key)
                    .ifPresentOrElse(existing -> verifyReplay(existing, requestHash), () -> { throw race; }));
        }
    }

    private static void verifyReplay(AdminCommandReplay replay, String requestHash) {
        if (!replay.getRequestHash().equals(requestHash)) {
            throw new ConflictException("The Idempotency-Key was already used for a different request");
        }
    }

    private static String requireReason(String value, String message) {
        if (value == null || value.isBlank() || value.trim().length() > 240) {
            throw new BusinessRuleException(message);
        }
        return value.trim();
    }

    private static final class CommandContext extends BusinessContext {
        private final Runnable command;
        private Boolean result;

        private CommandContext(Runnable command) {
            this.command = command;
        }
    }
}
