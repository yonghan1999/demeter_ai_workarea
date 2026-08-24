package com.demeter.backend.payment.application;

import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnRuntimeRole({RuntimeRole.MAINTENANCE})
@ConditionalOnProperty(
        prefix = "demeter.payment.reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PaymentReconciliationScheduler {

    private final PaymentReconciliationService reconciliationService;

    public PaymentReconciliationScheduler(PaymentReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(
            fixedDelayString = "${demeter.payment.reconciliation.fixed-delay:1h}",
            initialDelayString = "${demeter.payment.reconciliation.initial-delay:1m}")
    @SchedulerLock(name = "paymentReconciliation", lockAtMostFor = "30m", lockAtLeastFor = "1s")
    public void run() {
        reconciliationService.run();
    }
}
