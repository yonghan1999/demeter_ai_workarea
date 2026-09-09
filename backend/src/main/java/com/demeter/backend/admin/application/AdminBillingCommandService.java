package com.demeter.backend.admin.application;

import static com.demeter.backend.admin.application.AdminCommandInputs.requireReason;

import com.demeter.backend.audit.application.AuditService;
import com.demeter.backend.bill.domain.Bill;
import com.demeter.backend.bill.infrastructure.BillRepository;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.error.BusinessRuleException;
import com.demeter.backend.common.error.ConflictException;
import com.demeter.backend.common.error.ResourceNotFoundException;
import com.demeter.backend.common.idempotency.CanonicalValues;
import com.demeter.backend.common.idempotency.IdempotencyKeys;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.payment.domain.Payment;
import com.demeter.backend.payment.infrastructure.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminBillingCommandService {
    private static final int MAX_BATCH_BILLS = 100;
    private static final String BILL_DELETE = "admin.bill.delete";
    private static final String BILL_BATCH_DELETE = "admin.bill.batch-delete";
    private static final String BILL_RESTORE = "admin.bill.restore";
    private static final String PAYMENT_REVERSE = "admin.payment.reverse";

    private final BillRepository bills;
    private final PaymentRepository payments;
    private final AuditService audit;
    private final Clock clock;
    private final AdminCommandRunner runner;
    private final BusinessChain<BillContext, Boolean> deleteChain;
    private final BusinessChain<BatchBillContext, Boolean> batchDeleteChain;
    private final BusinessChain<BillContext, Boolean> restoreChain;
    private final BusinessChain<PaymentContext, Boolean> reversePaymentChain;

    public AdminBillingCommandService(BillRepository bills, PaymentRepository payments, AuditService audit,
            Clock clock, AdminCommandRunner runner) {
        this.bills = bills;
        this.payments = payments;
        this.audit = audit;
        this.clock = clock;
        this.runner = runner;
        this.deleteChain = buildDeleteChain();
        this.batchDeleteChain = buildBatchDeleteChain();
        this.restoreChain = buildRestoreChain();
        this.reversePaymentChain = buildReversePaymentChain();
    }

    public void deleteBill(long id, String reason, String idempotencyKey) {
        runner.execute(deleteChain, new BillContext(id, reason, idempotencyKey, BILL_DELETE));
    }

    public void deleteBills(Collection<Long> ids, String reason, String idempotencyKey) {
        runner.execute(batchDeleteChain, new BatchBillContext(ids, reason, idempotencyKey));
    }

    public void restoreBill(long id, String reason, String idempotencyKey) {
        runner.execute(restoreChain, new BillContext(id, reason, idempotencyKey, BILL_RESTORE));
    }

    public void reversePayment(long billId, long paymentId, String reason, String idempotencyKey) {
        runner.execute(reversePaymentChain, new PaymentContext(billId, paymentId, reason, idempotencyKey));
    }

    private BusinessChain<BillContext, Boolean> buildDeleteChain() {
        return BusinessChain.of(BILL_DELETE, BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("normalize-command", context -> context.normalize("删除原因不能为空")),
                        BusinessHandler.named("reserve-idempotency", context -> reserve(context)),
                        BusinessHandler.named("load-bill-for-update", context -> context.bill = requireBill(context.id)),
                        BusinessHandler.named("validate-deletion-state", context -> {
                            if (context.bill.getDeletedAt() != null) {
                                throw new ConflictException("The bill is already deleted");
                            }
                        }),
                        BusinessHandler.named("soft-delete-bill", context ->
                                context.bill.softDeleteBySystem(context.reason, clock.instant())),
                        BusinessHandler.named("persist-bill", context -> bills.saveAndFlush(context.bill)),
                        BusinessHandler.named("write-audit", context -> audit.recordSystem(
                                context.bill.getTenantId(), "ADMIN_BILL_DELETED", "BILL", context.bill.getId(),
                                Map.of("reason", context.reason)))),
                ignored -> Boolean.TRUE);
    }

    private BusinessChain<BatchBillContext, Boolean> buildBatchDeleteChain() {
        return BusinessChain.of(BILL_BATCH_DELETE, BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("normalize-command", context -> context.normalize()),
                        BusinessHandler.named("reserve-idempotency", context -> reserve(context)),
                        BusinessHandler.named("load-bills-for-update", context -> context.bills =
                                bills.findAllByIdsForUpdate(context.ids)),
                        BusinessHandler.named("validate-bill-set", context -> {
                            if (context.bills.size() != context.ids.size()) {
                                var existingIds = context.bills.stream().map(Bill::getId).collect(java.util.stream.Collectors.toSet());
                                List<Long> missing = context.ids.stream().filter(id -> !existingIds.contains(id)).toList();
                                throw new ResourceNotFoundException("Bills do not exist: " + missing);
                            }
                            if (context.bills.stream().anyMatch(bill -> bill.getDeletedAt() != null)) {
                                throw new ConflictException("One or more bills are already deleted");
                            }
                        }),
                        BusinessHandler.named("soft-delete-bills", context -> {
                            var now = clock.instant();
                            context.bills.forEach(bill -> bill.softDeleteBySystem(context.reason, now));
                        }),
                        BusinessHandler.named("persist-bills", context -> bills.saveAllAndFlush(context.bills)),
                        BusinessHandler.named("write-audit", context -> {
                            Map<Long, List<Long>> idsByTenant = new java.util.LinkedHashMap<>();
                            context.bills.forEach(bill -> idsByTenant
                                    .computeIfAbsent(bill.getTenantId(), ignored -> new java.util.ArrayList<>())
                                    .add(bill.getId()));
                            String batchId = "batch:" + CanonicalValues.builder()
                                    .addCollection(context.ids).digest().substring(0, 32);
                            idsByTenant.forEach((tenantId, tenantBillIds) -> audit.recordSystem(
                                    tenantId, "ADMIN_BILLS_BATCH_DELETED", "BILL_BATCH", batchId,
                                    Map.of("ids", tenantBillIds, "reason", context.reason)));
                        })),
                ignored -> Boolean.TRUE);
    }

    private BusinessChain<BillContext, Boolean> buildRestoreChain() {
        return BusinessChain.of(BILL_RESTORE, BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("normalize-command", context -> context.normalize("恢复原因不能为空")),
                        BusinessHandler.named("reserve-idempotency", context -> reserve(context)),
                        BusinessHandler.named("load-bill-for-update", context -> context.bill = requireBill(context.id)),
                        BusinessHandler.named("validate-deletion-state", context -> {
                            if (context.bill.getDeletedAt() == null) {
                                throw new ConflictException("The bill is not deleted");
                            }
                        }),
                        BusinessHandler.named("restore-bill", context -> context.bill.restoreBySystem(clock.instant())),
                        BusinessHandler.named("persist-bill", context -> bills.saveAndFlush(context.bill)),
                        BusinessHandler.named("write-audit", context -> audit.recordSystem(
                                context.bill.getTenantId(), "ADMIN_BILL_RESTORED", "BILL", context.bill.getId(),
                                Map.of("reason", context.reason)))),
                ignored -> Boolean.TRUE);
    }

    private BusinessChain<PaymentContext, Boolean> buildReversePaymentChain() {
        return BusinessChain.of(PAYMENT_REVERSE, BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("normalize-command", context -> context.normalize()),
                        BusinessHandler.named("reserve-idempotency", context -> reserve(context)),
                        BusinessHandler.named("load-bill-for-update", context -> context.bill = bills.findByIdForUpdate(
                                context.billId).orElseThrow(() -> new ResourceNotFoundException(
                                        "Bill " + context.billId + " does not exist"))),
                        BusinessHandler.named("load-payment-for-update", context -> context.payment = payments
                                .findByIdAndTenantIdForUpdate(context.paymentId, context.bill.getTenantId())
                                .orElseThrow(() -> new ResourceNotFoundException(
                                        "Payment " + context.paymentId + " does not exist"))),
                        BusinessHandler.named("validate-payment", context -> {
                            if (!context.payment.getBillId().equals(context.billId)) {
                                throw new ResourceNotFoundException("Payment does not belong to the bill");
                            }
                            if (context.payment.getStatus() != com.demeter.backend.payment.domain.PaymentStatus.ACTIVE) {
                                throw new ConflictException("The payment has already been reversed");
                            }
                            BigDecimal ledgerAmount = payments.sumActiveAmount(
                                    context.bill.getTenantId(), context.bill.getId(), BigDecimal.ZERO.setScale(2));
                            if (ledgerAmount.compareTo(context.bill.getPaidAmount()) != 0) {
                                throw new ConflictException(
                                        "The bill payment ledger is inconsistent; contact support before retrying");
                            }
                        }),
                        BusinessHandler.named("reverse-payment", context -> {
                            try {
                                context.payment.reverse(null, context.reason, context.key, context.hash, clock.instant());
                                context.bill.reversePaymentBySystem(context.payment.getAmount(), clock.instant());
                            } catch (IllegalArgumentException | IllegalStateException exception) {
                                throw new BusinessRuleException(exception.getMessage());
                            }
                        }),
                        BusinessHandler.named("persist-payment-and-bill", context -> {
                            payments.saveAndFlush(context.payment);
                            bills.saveAndFlush(context.bill);
                        }),
                        BusinessHandler.named("write-audit", context -> audit.recordSystem(
                                context.bill.getTenantId(), "ADMIN_PAYMENT_REVERSED", "PAYMENT", context.payment.getId(),
                                Map.of("billId", context.billId, "reason", context.reason)))),
                ignored -> Boolean.TRUE);
    }

    private Bill requireBill(long id) {
        return bills.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bill " + id + " does not exist"));
    }

    private <C extends BusinessContext & AdminIdempotentCommand> void reserve(C command) {
        if (runner.reserve(command, clock.instant()) == AdminCommandRunner.Reservation.REPLAY) {
            command.halt();
        }
    }

    private static final class BillContext extends BusinessContext implements AdminIdempotentCommand {
        private final long id;
        private final String requestedReason;
        private final String requestedKey;
        private final String operation;
        private String reason;
        private String key;
        private String hash;
        private Bill bill;

        private BillContext(long id, String reason, String key, String operation) {
            this.id = id;
            this.requestedReason = reason;
            this.requestedKey = key;
            this.operation = operation;
        }

        private void normalize(String message) {
            reason = requireReason(requestedReason, message);
            key = IdempotencyKeys.require(requestedKey);
            hash = CanonicalValues.sha256(id, reason);
        }

        @Override
        public String operationName() {
            return operation;
        }

        @Override
        public String idempotencyKey() { return key; }

        @Override
        public String requestHash() { return hash; }
    }

    private static final class BatchBillContext extends BusinessContext implements AdminIdempotentCommand {
        private final Collection<Long> requestedIds;
        private final String requestedReason;
        private final String requestedKey;
        private List<Long> ids;
        private String reason;
        private String key;
        private String hash;
        private List<Bill> bills;

        private BatchBillContext(Collection<Long> ids, String reason, String key) {
            this.requestedIds = ids;
            this.requestedReason = reason;
            this.requestedKey = key;
        }

        private void normalize() {
            if (requestedIds == null) throw new BusinessRuleException("至少选择一笔账单");
            ids = requestedIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
            if (ids.isEmpty() || ids.stream().anyMatch(id -> id < 1)) {
                throw new BusinessRuleException("至少选择一笔有效账单");
            }
            if (ids.size() > MAX_BATCH_BILLS) {
                throw new BusinessRuleException("单次最多处理 " + MAX_BATCH_BILLS + " 笔账单");
            }
            reason = requireReason(requestedReason, "删除原因不能为空");
            key = IdempotencyKeys.require(requestedKey);
            hash = CanonicalValues.builder().addCollection(ids).add(reason).digest();
        }

        @Override
        public String operationName() { return BILL_BATCH_DELETE; }

        @Override
        public String idempotencyKey() { return key; }

        @Override
        public String requestHash() { return hash; }
    }

    private static final class PaymentContext extends BusinessContext implements AdminIdempotentCommand {
        private final long billId;
        private final long paymentId;
        private final String requestedReason;
        private final String requestedKey;
        private String reason;
        private String key;
        private String hash;
        private Bill bill;
        private Payment payment;

        private PaymentContext(long billId, long paymentId, String reason, String key) {
            this.billId = billId;
            this.paymentId = paymentId;
            this.requestedReason = reason;
            this.requestedKey = key;
        }

        private void normalize() {
            reason = requireReason(requestedReason, "冲正原因不能为空");
            key = IdempotencyKeys.require(requestedKey);
            hash = CanonicalValues.sha256(billId, paymentId, reason);
        }

        @Override
        public String operationName() { return PAYMENT_REVERSE; }

        @Override
        public String idempotencyKey() { return key; }

        @Override
        public String requestHash() { return hash; }
    }
}
