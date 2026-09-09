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
import com.demeter.backend.payment.domain.Payment;
import com.demeter.backend.payment.infrastructure.PaymentRepository;
import com.demeter.backend.admin.infrastructure.AdminCommandReplay;
import com.demeter.backend.admin.infrastructure.AdminCommandReplayRepository;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminCommandService {
    private static final int MAX_BATCH_BILLS = 100;
    private static final String BILL_DELETE = "admin.bill.delete";
    private static final String BILL_BATCH_DELETE = "admin.bill.batch-delete";
    private static final String BILL_RESTORE = "admin.bill.restore";
    private static final String PAYMENT_REVERSE = "admin.payment.reverse";

    private final BillRepository bills;
    private final PaymentRepository payments;
    private final AdminCommandReplayRepository replays;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final BusinessChainExecutor executor;
    private final BusinessChain<CommandContext, Boolean> commandChain;

    public AdminCommandService(
            BillRepository bills,
            PaymentRepository payments,
            AdminCommandReplayRepository replays,
            AuditService audit,
            PlatformTransactionManager transactionManager,
            Clock clock,
            BusinessChainExecutor executor) {
        this.bills = bills;
        this.payments = payments;
        this.replays = replays;
        this.audit = audit;
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

    public void deleteBills(Collection<Long> ids, String reason, String idempotencyKey) {
        List<Long> normalizedIds = normalizeBillIds(ids);
        String normalizedReason = requireReason(reason, "删除原因不能为空");
        execute(BILL_BATCH_DELETE, idempotencyKey,
                CanonicalValues.builder().addCollection(normalizedIds).add(normalizedReason).digest(), () -> {
                    List<Bill> billsToDelete = bills.findAllByIdsForUpdate(normalizedIds);
                    if (billsToDelete.size() != normalizedIds.size()) {
                        var existingIds = billsToDelete.stream().map(Bill::getId).collect(java.util.stream.Collectors.toSet());
                        List<Long> missing = normalizedIds.stream().filter(id -> !existingIds.contains(id)).toList();
                        throw new ResourceNotFoundException("Bills do not exist: " + missing);
                    }
                    if (billsToDelete.stream().anyMatch(bill -> bill.getDeletedAt() != null)) {
                        throw new ConflictException("One or more bills are already deleted");
                    }
                    var now = clock.instant();
                    billsToDelete.forEach(bill -> bill.softDeleteBySystem(normalizedReason, now));
                    bills.saveAllAndFlush(billsToDelete);
                    Map<Long, List<Long>> idsByTenant = new java.util.LinkedHashMap<>();
                    billsToDelete.forEach(bill -> idsByTenant
                            .computeIfAbsent(bill.getTenantId(), ignored -> new java.util.ArrayList<>())
                            .add(bill.getId()));
                    String batchId = "batch:" + CanonicalValues.builder()
                            .addCollection(normalizedIds).digest().substring(0, 32);
                    idsByTenant.forEach((tenantId, tenantBillIds) -> audit.recordSystem(
                            tenantId,
                            "ADMIN_BILLS_BATCH_DELETED",
                            "BILL_BATCH",
                            batchId,
                            Map.of("ids", tenantBillIds, "reason", normalizedReason)));
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

    private static List<Long> normalizeBillIds(Collection<Long> ids) {
        if (ids == null) {
            throw new BusinessRuleException("至少选择一笔账单");
        }
        List<Long> normalized = ids.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (normalized.isEmpty() || normalized.stream().anyMatch(id -> id < 1)) {
            throw new BusinessRuleException("至少选择一笔有效账单");
        }
        if (normalized.size() > MAX_BATCH_BILLS) {
            throw new BusinessRuleException("单次最多处理 " + MAX_BATCH_BILLS + " 笔账单");
        }
        return normalized;
    }

    private static final class CommandContext extends BusinessContext {
        private final Runnable command;
        private Boolean result;

        private CommandContext(Runnable command) {
            this.command = command;
        }
    }
}
