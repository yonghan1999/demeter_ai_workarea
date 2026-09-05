package com.demeter.backend.admin.application;

import com.demeter.backend.bill.infrastructure.BillRepository;
import com.demeter.backend.audit.infrastructure.AuditEventRepository;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.identity.infrastructure.TenantRepository;
import com.demeter.backend.identity.infrastructure.UserAccountRepository;
import com.demeter.backend.ocr.infrastructure.OcrTaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class AdminQueryService {
    private final TenantRepository tenants;
    private final UserAccountRepository users;
    private final BillRepository bills;
    private final OcrTaskRepository ocr;
    private final AuditEventRepository audit;
    private final BusinessChainExecutor executor;
    private final BusinessChain<QueryContext, Page<?>> tenantChain;
    private final BusinessChain<QueryContext, Page<?>> userChain;
    private final BusinessChain<QueryContext, Page<?>> billChain;
    private final BusinessChain<QueryContext, Page<?>> ocrChain;
    private final BusinessChain<QueryContext, Page<?>> auditChain;

    public AdminQueryService(TenantRepository tenants, UserAccountRepository users, BillRepository bills,
            OcrTaskRepository ocr, AuditEventRepository audit, BusinessChainExecutor executor) {
        this.tenants = tenants; this.users = users; this.bills = bills; this.ocr = ocr; this.audit = audit; this.executor = executor;
        this.tenantChain = chain("admin.tenants", context -> tenants.findAllByOrderByCreatedAtDesc(context.pageable));
        this.userChain = chain("admin.users", context -> users.findAllByOrderByCreatedAtDesc(context.pageable));
        this.billChain = chain("admin.bills", context -> bills.findAll(context.pageable));
        this.ocrChain = chain("admin.ocr", context -> ocr.findAll(context.pageable));
        this.auditChain = chain("admin.audit", context -> audit.findAllByOrderByCreatedAtDescIdDesc(context.pageable));
    }
    public Page<?> tenants(Pageable pageable) { return executor.execute(tenantChain, new QueryContext(pageable)); }
    public Page<?> users(Pageable pageable) { return executor.execute(userChain, new QueryContext(pageable)); }
    public Page<?> bills(Pageable pageable) { return executor.execute(billChain, new QueryContext(pageable)); }
    public Page<?> ocr(Pageable pageable) { return executor.execute(ocrChain, new QueryContext(pageable)); }
    public Page<?> audit(Pageable pageable) { return executor.execute(auditChain, new QueryContext(pageable)); }

    private static BusinessChain<QueryContext, Page<?>> chain(String name, java.util.function.Function<QueryContext, Page<?>> query) {
        return BusinessChain.of(name, BusinessChainExecutionMode.READ_ONLY, java.util.List.of(
                BusinessHandler.named("validate-page", context -> {
                    if (context.pageable == null || context.pageable.getPageNumber() < 0
                            || context.pageable.getPageNumber() > 10_000
                            || context.pageable.getPageSize() < 1 || context.pageable.getPageSize() > 100) {
                        throw new IllegalArgumentException("Invalid management page");
                    }
                }),
                BusinessHandler.named("query", context -> context.result = query.apply(context))), context -> context.result);
    }
    private static final class QueryContext extends BusinessContext {
        private final Pageable pageable; private Page<?> result;
        private QueryContext(Pageable pageable) { this.pageable = pageable; }
    }
}
