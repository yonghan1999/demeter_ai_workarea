package com.demeter.backend.payment.application;

import com.demeter.backend.audit.application.AuditService;
import com.demeter.backend.bill.api.PageResponse;
import com.demeter.backend.bill.domain.Bill;
import com.demeter.backend.bill.infrastructure.BillRepository;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.error.BusinessRuleException;
import com.demeter.backend.common.error.ConflictException;
import com.demeter.backend.common.error.ResourceNotFoundException;
import com.demeter.backend.common.idempotency.CanonicalValues;
import com.demeter.backend.common.idempotency.IdempotencyKeys;
import com.demeter.backend.common.web.PaginationGuard;
import com.demeter.backend.common.web.PaginationProperties;
import com.demeter.backend.payment.api.CreatePaymentRequest;
import com.demeter.backend.payment.api.PaymentResponse;
import com.demeter.backend.payment.api.ReversePaymentRequest;
import com.demeter.backend.payment.domain.Payment;
import com.demeter.backend.payment.domain.PaymentStatus;
import com.demeter.backend.payment.infrastructure.PaymentRepository;
import com.demeter.backend.security.CurrentActor;
import com.demeter.backend.security.DemeterPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class PaymentService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final Duration MAX_FUTURE_PAYMENT_CLOCK_SKEW = Duration.ofMinutes(5);

    private final BillRepository billRepository;
    private final PaymentRepository paymentRepository;
    private final CurrentActor currentActor;
    private final AuditService auditService;
    private final BusinessChainExecutor chainExecutor;
    private final Clock clock;
    private final PaginationProperties pagination;

    private final BusinessChain<CreatePaymentContext, PaymentResponse> createChain;
    private final BusinessChain<ListPaymentsContext, PageResponse<PaymentResponse>> listChain;
    private final BusinessChain<ReversePaymentContext, PaymentResponse> reverseChain;

    public PaymentService(
            BillRepository billRepository,
            PaymentRepository paymentRepository,
            CurrentActor currentActor,
            AuditService auditService,
            BusinessChainExecutor chainExecutor,
            Clock clock,
            PaginationProperties pagination) {
        this.billRepository = billRepository;
        this.paymentRepository = paymentRepository;
        this.currentActor = currentActor;
        this.auditService = auditService;
        this.chainExecutor = chainExecutor;
        this.clock = clock;
        this.pagination = pagination;
        this.createChain = buildCreateChain();
        this.listChain = buildListChain();
        this.reverseChain = buildReverseChain();
    }

    @Transactional
    public PaymentResponse create(long billId, String idempotencyKey, CreatePaymentRequest request) {
        return chainExecutor.execute(createChain, new CreatePaymentContext(billId, idempotencyKey, request));
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> list(long billId, int page, int size) {
        return chainExecutor.execute(listChain, new ListPaymentsContext(billId, page, size));
    }

    @Transactional
    public PaymentResponse reverse(
            long billId,
            long paymentId,
            String idempotencyKey,
            ReversePaymentRequest request) {
        return chainExecutor.execute(
                reverseChain,
                new ReversePaymentContext(billId, paymentId, idempotencyKey, request));
    }

    private BusinessChain<CreatePaymentContext, PaymentResponse> buildCreateChain() {
        return BusinessChain.of(
                "payment.create",
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("normalize-command", context -> {
                            context.idempotencyKey = requireIdempotencyKey(context.requestedIdempotencyKey);
                            context.referenceNo = trimToNull(context.request.referenceNo());
                            context.note = trimToNull(context.request.note());
                            context.paidAt = context.request.paidAt() == null
                                    ? clock.instant()
                                    : context.request.paidAt();
                            if (context.paidAt.isAfter(clock.instant().plus(MAX_FUTURE_PAYMENT_CLOCK_SKEW))) {
                                throw new BusinessRuleException("收款时间不能晚于当前时间");
                            }
                            context.requestHash = paymentRequestHash(context);
                        }),
                        BusinessHandler.named("load-bill-for-update", context ->
                                context.bill = requireBillForUpdate(context.actor.tenantId(), context.billId)),
                        BusinessHandler.named("resolve-idempotency", context -> paymentRepository
                                .findByTenantIdAndIdempotencyKey(context.actor.tenantId(), context.idempotencyKey)
                                .ifPresent(existing -> {
                                    if (!existing.getRequestHash().equals(context.requestHash)) {
                                        throw new ConflictException(
                                                "The Idempotency-Key was already used for a different request");
                                    }
                                    context.payment = existing;
                                    context.result = PaymentResponse.from(existing, context.bill);
                                    context.halt();
                                })),
                        BusinessHandler.named("validate-ledger-consistency", context ->
                                validateLedgerConsistency(context.bill)),
                        BusinessHandler.named("validate-payment", context -> {
                            if (context.request.amount().compareTo(context.bill.getOutstandingAmount()) > 0) {
                                throw new BusinessRuleException("收款金额不能超过账单待收金额");
                            }
                        }),
                        BusinessHandler.named("apply-payment", context -> {
                            context.payment = new Payment(
                                    context.actor.tenantId(),
                                    context.bill.getId(),
                                    context.request.amount(),
                                    context.request.method(),
                                    context.paidAt,
                                    context.referenceNo,
                                    context.note,
                                    context.idempotencyKey,
                                    context.requestHash,
                                    context.actor.userId(),
                                    clock.instant());
                        }),
                        BusinessHandler.named("persist-payment", context -> {
                            context.payment = paymentRepository.saveAndFlush(context.payment);
                            try {
                                context.bill.registerPayment(
                                        context.payment.getAmount(), context.actor.userId(), clock.instant());
                            } catch (IllegalArgumentException exception) {
                                throw new BusinessRuleException(exception.getMessage());
                            }
                            billRepository.saveAndFlush(context.bill);
                        }),
                        BusinessHandler.named("write-audit", context -> auditService.record(
                                context.actor,
                                "PAYMENT_RECORDED",
                                "PAYMENT",
                                context.payment.getId(),
                                Map.of(
                                        "billId", context.bill.getId(),
                                        "amount", context.payment.getAmount(),
                                        "method", context.payment.getMethod().value()))),
                        BusinessHandler.named("map-response", context ->
                                context.result = PaymentResponse.from(context.payment, context.bill))),
                context -> context.result);
    }

    private BusinessChain<ListPaymentsContext, PageResponse<PaymentResponse>> buildListChain() {
        return BusinessChain.of(
                "payment.list",
                BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("load-bill", context -> context.bill = billRepository
                                .findByIdAndTenantIdAndDeletedAtIsNull(context.billId, context.actor.tenantId())
                                .orElseThrow(() -> new ResourceNotFoundException(
                                        "Bill " + context.billId + " does not exist"))),
                        BusinessHandler.named("validate-pagination", context -> PaginationGuard.requireValid(
                                context.page, context.size, pagination)),
                        BusinessHandler.named("build-page-request", context -> context.pageable = PageRequest.of(
                                context.page,
                                context.size,
                                Sort.by(Sort.Direction.DESC, "paidAt")
                                        .and(Sort.by(Sort.Direction.DESC, "id")))),
                        BusinessHandler.named("query-payments", context -> context.payments = paymentRepository
                                .findAllByBillIdAndTenantId(
                                        context.billId, context.actor.tenantId(), context.pageable)),
                        BusinessHandler.named("map-response", context -> context.result = PageResponse.from(
                                context.payments.map(payment -> PaymentResponse.from(payment, context.bill))))),
                context -> context.result);
    }

    private BusinessChain<ReversePaymentContext, PaymentResponse> buildReverseChain() {
        return BusinessChain.of(
                "payment.reverse",
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("normalize-command", context -> {
                            context.idempotencyKey = requireIdempotencyKey(context.requestedIdempotencyKey);
                            context.reason = context.request.reason().trim();
                            context.requestHash = CanonicalValues.sha256(
                                    context.billId, context.paymentId, context.reason);
                        }),
                        BusinessHandler.named("load-bill-for-update", context ->
                                context.bill = requireBillForUpdate(context.actor.tenantId(), context.billId)),
                        BusinessHandler.named("load-payment-for-update", context -> context.payment = paymentRepository
                                .findByIdAndTenantIdForUpdate(context.paymentId, context.actor.tenantId())
                                .orElseThrow(() -> new ResourceNotFoundException(
                                        "Payment " + context.paymentId + " does not exist"))),
                        BusinessHandler.named("validate-payment-owner", context -> {
                            if (!context.payment.getBillId().equals(context.billId)) {
                                throw new ResourceNotFoundException(
                                        "Payment " + context.paymentId + " does not exist for bill " + context.billId);
                            }
                        }),
                        BusinessHandler.named("resolve-idempotency", context -> {
                            if (context.payment.getStatus() == PaymentStatus.REVERSED) {
                                if (context.idempotencyKey.equals(context.payment.getReversalIdempotencyKey())
                                        && context.requestHash.equals(context.payment.getReversalRequestHash())) {
                                    context.result = PaymentResponse.from(context.payment, context.bill);
                                    context.halt();
                                    return;
                                }
                                throw new ConflictException("The payment has already been reversed");
                            }
                        }),
                        BusinessHandler.named("validate-ledger-consistency", context ->
                                validateLedgerConsistency(context.bill)),
                        BusinessHandler.named("apply-reversal", context -> {
                            try {
                                context.payment.reverse(
                                        context.actor.userId(),
                                        context.reason,
                                        context.idempotencyKey,
                                        context.requestHash,
                                        clock.instant());
                            } catch (IllegalArgumentException | IllegalStateException exception) {
                                throw new BusinessRuleException(exception.getMessage());
                            }
                        }),
                        BusinessHandler.named("persist-reversal", context -> {
                            paymentRepository.saveAndFlush(context.payment);
                            try {
                                context.bill.reversePayment(
                                        context.payment.getAmount(), context.actor.userId(), clock.instant());
                            } catch (IllegalArgumentException exception) {
                                throw new BusinessRuleException(exception.getMessage());
                            }
                            billRepository.saveAndFlush(context.bill);
                        }),
                        BusinessHandler.named("write-audit", context -> auditService.record(
                                context.actor,
                                "PAYMENT_REVERSED",
                                "PAYMENT",
                                context.payment.getId(),
                                Map.of(
                                        "billId", context.bill.getId(),
                                        "amount", context.payment.getAmount(),
                                        "reason", context.reason))),
                        BusinessHandler.named("map-response", context ->
                                context.result = PaymentResponse.from(context.payment, context.bill))),
                context -> context.result);
    }

    private Bill requireBillForUpdate(long tenantId, long billId) {
        return billRepository.findByIdAndTenantIdForUpdate(billId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill " + billId + " does not exist"));
    }

    private void validateLedgerConsistency(Bill bill) {
        BigDecimal ledgerAmount = paymentRepository.sumActiveAmount(
                bill.getTenantId(),
                bill.getId(),
                BigDecimal.ZERO.setScale(2));
        if (ledgerAmount.compareTo(bill.getPaidAmount()) != 0) {
            throw new ConflictException(
                    "The bill payment ledger is inconsistent; contact support before retrying");
        }
    }

    private <C extends ActorContext> void resolveActor(C context) {
        context.actor = currentActor.require();
    }

    private static String requireIdempotencyKey(String value) {
        return IdempotencyKeys.require(value);
    }

    private static String paymentRequestHash(CreatePaymentContext context) {
        CreatePaymentRequest request = context.request;
        String paidAt = request.paidAt() == null ? "" : request.paidAt().toString();
        return CanonicalValues.sha256(
                context.billId,
                request.amount().stripTrailingZeros().toPlainString(),
                request.method().name(),
                request.paidAt(),
                context.referenceNo,
                context.note);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }


    private abstract static class ActorContext extends BusinessContext {
        protected DemeterPrincipal actor;
    }

    private static final class CreatePaymentContext extends ActorContext {
        private final long billId;
        private final String requestedIdempotencyKey;
        private final CreatePaymentRequest request;
        private String idempotencyKey;
        private String requestHash;
        private String referenceNo;
        private String note;
        private Instant paidAt;
        private Bill bill;
        private Payment payment;
        private PaymentResponse result;

        private CreatePaymentContext(long billId, String requestedIdempotencyKey, CreatePaymentRequest request) {
            this.billId = billId;
            this.requestedIdempotencyKey = requestedIdempotencyKey;
            this.request = request;
        }
    }

    private static final class ListPaymentsContext extends ActorContext {
        private final long billId;
        private final int page;
        private final int size;
        private Bill bill;
        private PageRequest pageable;
        private Page<Payment> payments;
        private PageResponse<PaymentResponse> result;

        private ListPaymentsContext(long billId, int page, int size) {
            this.billId = billId;
            this.page = page;
            this.size = size;
        }
    }

    private static final class ReversePaymentContext extends ActorContext {
        private final long billId;
        private final long paymentId;
        private final String requestedIdempotencyKey;
        private final ReversePaymentRequest request;
        private String idempotencyKey;
        private String requestHash;
        private String reason;
        private Payment payment;
        private Bill bill;
        private PaymentResponse result;

        private ReversePaymentContext(
                long billId,
                long paymentId,
                String requestedIdempotencyKey,
                ReversePaymentRequest request) {
            this.billId = billId;
            this.paymentId = paymentId;
            this.requestedIdempotencyKey = requestedIdempotencyKey;
            this.request = request;
        }
    }

}
