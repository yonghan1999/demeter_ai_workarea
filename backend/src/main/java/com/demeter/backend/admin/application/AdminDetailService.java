package com.demeter.backend.admin.application;

import com.demeter.backend.admin.application.AdminQueryFilters.AuditFilter;
import com.demeter.backend.admin.application.AdminRows.AuditRow;
import com.demeter.backend.admin.application.AdminRows.BillDetailRow;
import com.demeter.backend.admin.application.AdminRows.PaymentRow;
import com.demeter.backend.admin.infrastructure.AdminSpecifications;
import com.demeter.backend.audit.infrastructure.AuditEventRepository;
import com.demeter.backend.bill.domain.Bill;
import com.demeter.backend.bill.infrastructure.BillRepository;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.error.ResourceNotFoundException;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.payment.infrastructure.PaymentRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class AdminDetailService {
    private static final int MAX_AUDIT_EVENTS = 50;

    private final BillRepository bills;
    private final PaymentRepository payments;
    private final AuditEventRepository audit;
    private final BusinessChainExecutor executor;
    private final BusinessChain<BillDetailContext, BillDetailRow> billChain;

    public AdminDetailService(BillRepository bills, PaymentRepository payments, AuditEventRepository audit,
            BusinessChainExecutor executor) {
        this.bills = bills;
        this.payments = payments;
        this.audit = audit;
        this.executor = executor;
        this.billChain = BusinessChain.of("admin.bill.detail", BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("validate-id", context -> {
                            if (context.billId < 1) throw new IllegalArgumentException("Invalid bill id");
                        }),
                        BusinessHandler.named("load-detail", context -> context.result = load(context.billId))),
                context -> context.result);
    }

    @Transactional(readOnly = true)
    public BillDetailRow bill(long id) {
        return executor.execute(billChain, new BillDetailContext(id));
    }

    private BillDetailRow load(long id) {
        Bill bill = bills.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bill " + id + " does not exist"));
        List<PaymentRow> paymentRows = payments.findAllByBillIdAndTenantIdOrderByPaidAtDescIdDesc(id, bill.getTenantId())
                .stream().map(PaymentRow::from).toList();
        List<AuditRow> auditRows = audit.findAll(
                        AdminSpecifications.auditEvents(new AuditFilter(
                                bill.getTenantId(), null, "BILL", String.valueOf(id))),
                        PageRequest.of(0, MAX_AUDIT_EVENTS,
                                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))))
                .map(AuditRow::from).getContent();
        return BillDetailRow.from(bill, paymentRows, auditRows);
    }

    private static final class BillDetailContext extends BusinessContext {
        private final long billId;
        private BillDetailRow result;

        private BillDetailContext(long billId) {
            this.billId = billId;
        }
    }
}
