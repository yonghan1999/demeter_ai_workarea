package com.demeter.backend.admin.application;

import com.demeter.backend.admin.application.AdminQueryFilters.AuditFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.BillFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.OcrFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.PaymentFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.TenantFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.UserFilter;
import com.demeter.backend.admin.application.AdminRows.AuditRow;
import com.demeter.backend.admin.application.AdminRows.BillRow;
import com.demeter.backend.admin.application.AdminRows.LedgerDiscrepancyRow;
import com.demeter.backend.admin.application.AdminRows.OcrRow;
import com.demeter.backend.admin.application.AdminRows.PaymentRow;
import com.demeter.backend.admin.application.AdminRows.TenantRow;
import com.demeter.backend.admin.application.AdminRows.UserRow;
import com.demeter.backend.admin.infrastructure.AdminSpecifications;
import com.demeter.backend.audit.infrastructure.AuditEventRepository;
import com.demeter.backend.bill.infrastructure.BillRepository;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.web.PaginationProperties;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.identity.infrastructure.TenantRepository;
import com.demeter.backend.identity.infrastructure.UserAccountRepository;
import com.demeter.backend.ocr.infrastructure.OcrTaskRepository;
import com.demeter.backend.payment.infrastructure.PaymentRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminQueryService {
    private static final int MAX_RECONCILIATION_RESULTS = 100;

    private final TenantRepository tenants;
    private final UserAccountRepository users;
    private final BillRepository bills;
    private final OcrTaskRepository ocr;
    private final AuditEventRepository audit;
    private final PaymentRepository payments;
    private final BusinessChainExecutor executor;
    private final PaginationProperties pagination;

    public AdminQueryService(TenantRepository tenants, UserAccountRepository users, BillRepository bills,
            OcrTaskRepository ocr, AuditEventRepository audit, PaymentRepository payments,
            BusinessChainExecutor executor, PaginationProperties pagination) {
        this.tenants = tenants;
        this.users = users;
        this.bills = bills;
        this.ocr = ocr;
        this.audit = audit;
        this.payments = payments;
        this.executor = executor;
        this.pagination = pagination;
    }

    public Page<TenantRow> tenants(TenantFilter filter, Pageable pageable) {
        return query("admin.tenants", pageable, value -> tenants.findAll(AdminSpecifications.tenants(filter), value).map(TenantRow::from));
    }

    public Page<UserRow> users(UserFilter filter, Pageable pageable) {
        return query("admin.users", pageable, value -> users.findAll(AdminSpecifications.users(filter), value).map(UserRow::from));
    }

    public Page<BillRow> bills(BillFilter filter, Pageable pageable) {
        return query("admin.bills", pageable, value -> bills.findAll(AdminSpecifications.bills(filter), value).map(BillRow::from));
    }

    public Page<OcrRow> ocr(OcrFilter filter, Pageable pageable) {
        return query("admin.ocr", pageable, value -> ocr.findAll(AdminSpecifications.ocrTasks(filter), value).map(OcrRow::from));
    }

    public Page<AuditRow> audit(AuditFilter filter, Pageable pageable) {
        return query("admin.audit", pageable,
                value -> audit.findAll(AdminSpecifications.auditEvents(filter), value).map(AuditRow::from));
    }

    public Page<PaymentRow> payments(PaymentFilter filter, Pageable pageable) {
        return query("admin.payments", pageable,
                value -> payments.findAll(AdminSpecifications.payments(filter), value).map(PaymentRow::from));
    }

    public List<LedgerDiscrepancyRow> ledgerDiscrepancies() {
        Pageable limit = PageRequest.of(0, Math.min(MAX_RECONCILIATION_RESULTS, pagination.maxSize()));
        return query("admin.payment-reconciliation", limit,
                ignored -> payments.findLedgerDiscrepancies(BigDecimal.ZERO.setScale(2), limit).stream()
                        .map(LedgerDiscrepancyRow::from).toList());
    }

    private <R> R query(String name, Pageable pageable, Function<Pageable, R> operation) {
        BusinessChain<QueryContext<R>, R> chain = BusinessChain.of(name, BusinessChainExecutionMode.READ_ONLY,
                java.util.List.of(
                        BusinessHandler.named("validate-page", context -> validate(context.pageable)),
                        BusinessHandler.named("query", context -> context.result = operation.apply(context.pageable))),
                context -> context.result);
        return executor.execute(chain, new QueryContext<>(pageable));
    }

    private void validate(Pageable pageable) {
        if (pageable == null || pageable.getPageNumber() < 0 || pageable.getPageNumber() >= pagination.maxPage()
                || pageable.getPageSize() < 1 || pageable.getPageSize() > pagination.maxSize()) {
            throw new IllegalArgumentException("Invalid management page");
        }
    }

    private static final class QueryContext<R> extends BusinessContext {
        private final Pageable pageable;
        private R result;

        private QueryContext(Pageable pageable) {
            this.pageable = pageable;
        }
    }
}
