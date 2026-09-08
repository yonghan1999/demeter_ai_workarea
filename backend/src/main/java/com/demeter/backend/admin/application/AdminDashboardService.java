package com.demeter.backend.admin.application;

import com.demeter.backend.bill.domain.BillStatus;
import com.demeter.backend.bill.infrastructure.BillRepository;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.identity.infrastructure.TenantRepository;
import com.demeter.backend.identity.infrastructure.UserAccountRepository;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.ocr.infrastructure.OcrTaskRepository;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminDashboardService {
    private final BillRepository bills;
    private final TenantRepository tenants;
    private final UserAccountRepository users;
    private final OcrTaskRepository ocr;
    private final BusinessChainExecutor executor;
    private final BusinessChain<DashboardContext, Dashboard> dashboardChain;

    public AdminDashboardService(BillRepository bills, TenantRepository tenants, UserAccountRepository users,
            OcrTaskRepository ocr, BusinessChainExecutor executor) {
        this.bills = bills; this.tenants = tenants; this.users = users; this.ocr = ocr;
        this.executor = executor;
        this.dashboardChain = BusinessChain.of(
                "admin.dashboard",
                BusinessChainExecutionMode.READ_ONLY,
                java.util.List.of(BusinessHandler.named("query-statistics", context -> context.result = query())) ,
                context -> context.result);
    }

    public Dashboard dashboard() {
        return executor.execute(dashboardChain, new DashboardContext());
    }

    private Dashboard query() {
        Map<String, Long> ocrCounts = ocr.countTasksByStatus().stream()
                .collect(Collectors.toMap(item -> item.status().name(), item -> item.taskCount()));
        return new Dashboard(tenants.count(), users.count(), bills.countByDeletedAtIsNull(),
                bills.countByStatusAndDeletedAtIsNull(BillStatus.UNPAID),
                bills.countByStatusAndDeletedAtIsNull(BillStatus.PARTIALLY_PAID), ocrCounts);
    }

    public record Dashboard(long tenantCount, long userCount, long billCount, long unpaidBills,
            long partiallyPaidBills, Map<String, Long> ocrCounts) {}

    private static final class DashboardContext extends BusinessContext {
        private Dashboard result;
    }
}
