package com.demeter.backend.admin.application;

import com.demeter.backend.bill.infrastructure.BillRepository;
import com.demeter.backend.audit.infrastructure.AuditEventRepository;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.web.PaginationProperties;
import com.demeter.backend.identity.infrastructure.TenantRepository;
import com.demeter.backend.identity.infrastructure.UserAccountRepository;
import com.demeter.backend.ocr.infrastructure.OcrTaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.demeter.backend.admin.application.AdminRows.AuditRow;
import com.demeter.backend.admin.application.AdminRows.BillRow;
import com.demeter.backend.admin.application.AdminRows.OcrRow;
import com.demeter.backend.admin.application.AdminRows.TenantRow;
import com.demeter.backend.admin.application.AdminRows.UserRow;

@Service
public class AdminQueryService {
    private final TenantRepository tenants;
    private final UserAccountRepository users;
    private final BillRepository bills;
    private final OcrTaskRepository ocr;
    private final AuditEventRepository audit;
    private final BusinessChainExecutor executor;
    private final PaginationProperties pagination;
    private final BusinessChain<QueryContext<TenantRow>, Page<TenantRow>> tenantChain;
    private final BusinessChain<QueryContext<UserRow>, Page<UserRow>> userChain;
    private final BusinessChain<QueryContext<BillRow>, Page<BillRow>> billChain;
    private final BusinessChain<QueryContext<OcrRow>, Page<OcrRow>> ocrChain;
    private final BusinessChain<QueryContext<AuditRow>, Page<AuditRow>> auditChain;

    public AdminQueryService(TenantRepository tenants, UserAccountRepository users, BillRepository bills,
            OcrTaskRepository ocr, AuditEventRepository audit, BusinessChainExecutor executor,
            PaginationProperties pagination) {
        this.tenants = tenants; this.users = users; this.bills = bills; this.ocr = ocr; this.audit = audit; this.executor = executor;
        this.pagination = pagination;
        this.tenantChain = chain("admin.tenants", context -> tenants.findAllByOrderByCreatedAtDesc(context.pageable)
                .map(TenantRow::from));
        this.userChain = chain("admin.users", context -> users.findAllByOrderByCreatedAtDesc(context.pageable)
                .map(UserRow::from));
        this.billChain = chain("admin.bills", context -> bills.findAll(context.pageable).map(BillRow::from));
        this.ocrChain = chain("admin.ocr", context -> ocr.findAll(context.pageable).map(OcrRow::from));
        this.auditChain = chain("admin.audit", context -> audit.findAllByOrderByCreatedAtDescIdDesc(context.pageable)
                .map(AuditRow::from));
    }
    public Page<TenantRow> tenants(Pageable pageable) { return executor.execute(tenantChain, new QueryContext<>(pageable)); }
    public Page<UserRow> users(Pageable pageable) { return executor.execute(userChain, new QueryContext<>(pageable)); }
    public Page<BillRow> bills(Pageable pageable) { return executor.execute(billChain, new QueryContext<>(pageable)); }
    public Page<OcrRow> ocr(Pageable pageable) { return executor.execute(ocrChain, new QueryContext<>(pageable)); }
    public Page<AuditRow> audit(Pageable pageable) { return executor.execute(auditChain, new QueryContext<>(pageable)); }

    private <R> BusinessChain<QueryContext<R>, Page<R>> chain(
            String name, java.util.function.Function<QueryContext<R>, Page<R>> query) {
        return BusinessChain.of(name, BusinessChainExecutionMode.READ_ONLY, java.util.List.of(
                BusinessHandler.named("validate-page", context -> {
                    if (context.pageable == null || context.pageable.getPageNumber() < 0
                            || context.pageable.getPageNumber() >= pagination.maxPage()
                            || context.pageable.getPageSize() < 1 || context.pageable.getPageSize() > pagination.maxSize()) {
                        throw new IllegalArgumentException("Invalid management page");
                    }
                }),
                BusinessHandler.named("query", context -> context.result = query.apply(context))), context -> context.result);
    }
    private static final class QueryContext<R> extends BusinessContext {
        private final Pageable pageable; private Page<R> result;
        private QueryContext(Pageable pageable) { this.pageable = pageable; }
    }
}
