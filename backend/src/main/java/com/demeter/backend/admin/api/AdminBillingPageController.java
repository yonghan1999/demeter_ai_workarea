package com.demeter.backend.admin.api;

import com.demeter.backend.admin.application.AdminBillingCommandService;
import com.demeter.backend.admin.application.AdminQueryFilters.BillFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.DeletionStatus;
import com.demeter.backend.admin.application.AdminQueryFilters.PaymentFilter;
import com.demeter.backend.admin.application.AdminQueryService;
import com.demeter.backend.bill.domain.BillStatus;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.payment.domain.PaymentStatus;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/admin")
public class AdminBillingPageController {
    private final AdminQueryService queries;
    private final AdminBillingCommandService commands;
    private final AdminPageRequestFactory pages;
    private final AdminOperationFeedback feedback;

    public AdminBillingPageController(AdminQueryService queries, AdminBillingCommandService commands,
            AdminPageRequestFactory pages, AdminOperationFeedback feedback) {
        this.queries = queries;
        this.commands = commands;
        this.pages = pages;
        this.feedback = feedback;
    }

    @GetMapping("/bills")
    String billList(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) BillStatus status,
            @RequestParam(defaultValue = "ALL") DeletionStatus deletion, Model model) {
        BillFilter filter = new BillFilter(pages.queryText(keyword), tenantId, status, deletion);
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", BillStatus.values());
        model.addAttribute("deletionStatuses", DeletionStatus.values());
        model.addAttribute("page", queries.bills(filter, pages.descending(page, "updatedAt")));
        return "admin/bills";
    }

    @PostMapping("/bills/{id}/delete")
    String deleteBill(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return feedback.execute(() -> commands.deleteBill(id, reason, idempotencyKey), redirect,
                "/admin/bills", "账单已删除");
    }

    @PostMapping("/bills/batch-delete")
    String deleteBills(@RequestParam(name = "billIds", required = false) List<Long> billIds,
            @RequestParam String reason, @RequestParam String idempotencyKey, RedirectAttributes redirect) {
        return feedback.execute(() -> commands.deleteBills(billIds, reason, idempotencyKey), redirect,
                "/admin/bills", "选中的账单已删除");
    }

    @PostMapping("/bills/{id}/restore")
    String restoreBill(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return feedback.execute(() -> commands.restoreBill(id, reason, idempotencyKey), redirect,
                "/admin/bills", "账单已恢复");
    }

    @GetMapping("/payments")
    String paymentList(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Long id, @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long billId,
            @RequestParam(required = false) PaymentStatus status, Model model) {
        PaymentFilter filter = new PaymentFilter(id, tenantId, billId, status);
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", PaymentStatus.values());
        model.addAttribute("page", queries.payments(filter, pages.descending(page, "paidAt")));
        return "admin/payments";
    }

    @GetMapping("/payment-reconciliation")
    String paymentReconciliation(Model model) {
        model.addAttribute("items", queries.ledgerDiscrepancies());
        return "admin/payment-reconciliation";
    }

    @PostMapping("/payments/{paymentId}/reverse")
    String reversePayment(@PathVariable long paymentId, @RequestParam long billId, @RequestParam String reason,
            @RequestParam String idempotencyKey, RedirectAttributes redirect) {
        return feedback.execute(() -> commands.reversePayment(billId, paymentId, reason, idempotencyKey), redirect,
                "/admin/payments", "收款已冲正");
    }
}
