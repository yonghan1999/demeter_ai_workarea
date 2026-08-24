package com.demeter.backend.payment.application;

import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.payment.infrastructure.PaymentLedgerDiscrepancy;
import com.demeter.backend.payment.infrastructure.PaymentReconciliationProperties;
import com.demeter.backend.payment.infrastructure.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.MAINTENANCE)
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentReconciliationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final BusinessChainExecutor chainExecutor;
    private final AtomicInteger discrepancyGauge = new AtomicInteger();
    private final Counter discrepancyCounter;
    private final BusinessChain<ReconciliationContext, ReconciliationResult> reconciliationChain;

    public PaymentReconciliationService(
            PaymentRepository paymentRepository,
            PaymentReconciliationProperties properties,
            PlatformTransactionManager transactionManager,
            BusinessChainExecutor chainExecutor,
            MeterRegistry meterRegistry) {
        this.paymentRepository = paymentRepository;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setReadOnly(true);
        this.chainExecutor = chainExecutor;
        meterRegistry.gauge("demeter.payment.reconciliation.discrepancies", discrepancyGauge);
        this.discrepancyCounter = Counter.builder("demeter.payment.reconciliation.detected")
                .description("Payment ledger discrepancies detected during reconciliation")
                .register(meterRegistry);
        this.reconciliationChain = buildReconciliationChain();
    }

    public ReconciliationResult run() {
        return chainExecutor.execute(reconciliationChain, new ReconciliationContext());
    }

    private BusinessChain<ReconciliationContext, ReconciliationResult> buildReconciliationChain() {
        return BusinessChain.of(
                "payment.reconcile",
                BusinessChainExecutionMode.SCHEDULED,
                List.of(
                        BusinessHandler.named("scan-ledger", context -> context.discrepancies =
                                transactionTemplate.execute(status -> paymentRepository.findLedgerDiscrepancies(
                                        BigDecimal.ZERO.setScale(2),
                                        PageRequest.of(0, properties.sampleSize() + 1)))),
                        BusinessHandler.named("record-metrics", context -> {
                            context.truncated = context.discrepancies.size() > properties.sampleSize();
                            context.samples = context.discrepancies.stream()
                                    .limit(properties.sampleSize())
                                    .toList();
                            int observed = context.samples.size();
                            discrepancyGauge.set(observed);
                            if (observed > 0) {
                                discrepancyCounter.increment(observed);
                            }
                        }),
                        BusinessHandler.named("report-discrepancies", context -> {
                            if (context.samples.isEmpty()) {
                                log.debug("Payment ledger reconciliation completed without discrepancies");
                                return;
                            }
                            for (PaymentLedgerDiscrepancy discrepancy : context.samples) {
                                log.error(
                                        "Payment ledger discrepancy: tenantId={}, billId={}, recordedAmount={}, ledgerAmount={}",
                                        discrepancy.tenantId(),
                                        discrepancy.billId(),
                                        discrepancy.recordedAmount(),
                                        discrepancy.ledgerAmount());
                            }
                            if (context.truncated) {
                                log.error("Additional payment ledger discrepancies were omitted from this scan");
                            }
                        }),
                        BusinessHandler.named("build-result", context -> context.result = new ReconciliationResult(
                                context.samples.size(), context.truncated))),
                context -> context.result);
    }

    public record ReconciliationResult(int sampledDiscrepancies, boolean truncated) {
    }

    private static final class ReconciliationContext extends BusinessContext {
        private List<PaymentLedgerDiscrepancy> discrepancies = List.of();
        private List<PaymentLedgerDiscrepancy> samples = List.of();
        private boolean truncated;
        private ReconciliationResult result;
    }
}
