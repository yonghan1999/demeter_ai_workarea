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
import com.demeter.backend.payment.domain.PaymentStatus;
import com.demeter.backend.payment.infrastructure.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminPaymentCommandService {
    private static final String PAYMENT_REVERSE = "admin.payment.reverse";

    private final BillRepository bills;
    private final PaymentRepository payments;
    private final AuditService audit;
    private final Clock clock;
    private final AdminCommandRunner runner;
    private final BusinessChain<PaymentContext, Boolean> reverseChain;

    public AdminPaymentCommandService(BillRepository bills, PaymentRepository payments, AuditService audit,
            Clock clock, AdminCommandRunner runner) {
        this.bills = bills;
        this.payments = payments;
        this.audit = audit;
        this.clock = clock;
        this.runner = runner;
        this.reverseChain = buildReverseChain();
    }

    public void reverse(long billId, long paymentId, String reason, String idempotencyKey) {
        runner.execute(reverseChain, new PaymentContext(billId, paymentId, reason, idempotencyKey));
    }

    private BusinessChain<PaymentContext, Boolean> buildReverseChain() {
        return BusinessChain.of(PAYMENT_REVERSE, BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("normalize-command", PaymentContext::normalize),
                        BusinessHandler.named("reserve-idempotency", context -> {
                            if (runner.reserve(context, clock.instant()) == AdminCommandRunner.Reservation.REPLAY) {
                                context.halt();
                            }
                        }),
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
                            if (context.payment.getStatus() != PaymentStatus.ACTIVE) {
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
