package com.demeter.backend.admin.api;

import com.demeter.backend.admin.application.AdminDashboardService;
import com.demeter.backend.admin.application.AdminCommandService;
import com.demeter.backend.admin.application.AdminQueryFilters.AuditFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.BillFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.DeletionStatus;
import com.demeter.backend.admin.application.AdminQueryFilters.OcrFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.PaymentFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.TenantFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.UserFilter;
import com.demeter.backend.admin.security.AdminSessionService;
import com.demeter.backend.admin.application.AdminQueryService;
import com.demeter.backend.common.web.PaginationProperties;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.demeter.backend.bill.domain.BillStatus;
import com.demeter.backend.identity.domain.TenantStatus;
import com.demeter.backend.identity.domain.UserStatus;
import com.demeter.backend.ocr.domain.OcrTaskStatus;
import com.demeter.backend.payment.domain.PaymentStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseCookie;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/admin")
public class AdminPageController {
    private final AdminSessionService sessions;
    private final AdminDashboardService dashboard;
    private final AdminQueryService queries;
    private final AdminCommandService commands;
    private final PaginationProperties pagination;

    public AdminPageController(AdminSessionService sessions, AdminDashboardService dashboard,
            AdminQueryService queries, AdminCommandService commands, PaginationProperties pagination) {
        this.sessions = sessions; this.dashboard = dashboard; this.queries = queries; this.commands = commands; this.pagination = pagination;
    }

    @GetMapping("/login")
    String login(Model model) { model.addAttribute("enabled", sessions.enabled()); return "admin/login"; }

    @GetMapping(value = "/admin.css", produces = "text/css")
    ResponseEntity<Resource> stylesheet() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/css"))
                .body(new ClassPathResource("static/admin/admin.css"));
    }

    @GetMapping(value = "/admin.js", produces = "application/javascript")
    ResponseEntity<Resource> script() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .body(new ClassPathResource("static/admin/admin.js"));
    }

    @PostMapping("/login")
    String login(@RequestParam String accessToken, HttpServletRequest request, HttpServletResponse response) {
        if (!sessions.verifyAccessToken(accessToken)) return "redirect:/admin/login?error";
        ResponseCookie cookie = ResponseCookie.from(AdminSessionService.COOKIE_NAME, sessions.createSession())
                .httpOnly(true)
                .secure(request.isSecure())
                .path(adminCookiePath(request))
                .maxAge(sessions.sessionTtlSeconds())
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
        response.setHeader("Cache-Control", "no-store");
        return "redirect:/admin";
    }

    @PostMapping("/logout")
    String logout(HttpServletRequest request, HttpServletResponse response) {
        if (request.getCookies() != null) for (Cookie c : request.getCookies())
            if (AdminSessionService.COOKIE_NAME.equals(c.getName())) sessions.revoke(c.getValue());
        ResponseCookie expired = ResponseCookie.from(AdminSessionService.COOKIE_NAME, "")
                .httpOnly(true).secure(request.isSecure()).path(adminCookiePath(request)).maxAge(0).sameSite("Lax").build();
        response.addHeader("Set-Cookie", expired.toString());
        response.setHeader("Cache-Control", "no-store");
        return "redirect:/admin/login";
    }

    @GetMapping
    String home(Model model) { model.addAttribute("dashboard", dashboard.dashboard()); return "admin/dashboard"; }

    @GetMapping("/tenants")
    String tenantList(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) TenantStatus status, Model model) {
        TenantFilter filter = new TenantFilter(queryText(keyword), status);
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", TenantStatus.values());
        model.addAttribute("page", queries.tenants(filter, pageRequest(page, "createdAt")));
        return "admin/tenants";
    }

    @PostMapping("/tenants/{id}/suspend")
    String suspendTenant(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return change(() -> commands.suspendTenant(id, reason, idempotencyKey), redirect, "/admin/tenants", "租户已暂停");
    }

    @PostMapping("/tenants/{id}/activate")
    String activateTenant(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return change(() -> commands.activateTenant(id, reason, idempotencyKey), redirect, "/admin/tenants", "租户已恢复");
    }

    @GetMapping("/users")
    String userList(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Long id, @RequestParam(required = false) Long tenantId,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) UserStatus status, Model model) {
        UserFilter filter = new UserFilter(id, tenantId, queryText(keyword), status);
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", UserStatus.values());
        model.addAttribute("page", queries.users(filter, pageRequest(page, "createdAt")));
        return "admin/users";
    }

    @PostMapping("/users/{id}/disable")
    String disableUser(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return change(() -> commands.disableUser(id, reason, idempotencyKey), redirect, "/admin/users", "用户已禁用");
    }

    @PostMapping("/users/{id}/enable")
    String enableUser(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return change(() -> commands.enableUser(id, reason, idempotencyKey), redirect, "/admin/users", "用户已启用");
    }

    @PostMapping("/users/{id}/revoke-sessions")
    String revokeUserSessions(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return change(() -> commands.revokeUserSessions(id, reason, idempotencyKey), redirect, "/admin/users", "用户会话已撤销");
    }

    @GetMapping("/bills")
    String billList(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) BillStatus status,
            @RequestParam(defaultValue = "ALL") DeletionStatus deletion, Model model) {
        BillFilter filter = new BillFilter(queryText(keyword), tenantId, status, deletion);
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", BillStatus.values());
        model.addAttribute("deletionStatuses", DeletionStatus.values());
        model.addAttribute("page", queries.bills(filter, pageRequest(page, "updatedAt")));
        return "admin/bills";
    }

    @GetMapping("/ocr")
    String ocrList(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String taskId, @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) OcrTaskStatus status,
            @RequestParam(defaultValue = "") String errorCode, Model model) {
        OcrFilter filter = new OcrFilter(queryText(taskId), tenantId, status, queryText(errorCode));
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", OcrTaskStatus.values());
        model.addAttribute("page", queries.ocr(filter, pageRequest(page, "createdAt")));
        return "admin/ocr";
    }

    @PostMapping("/bills/{id}/delete")
    String deleteBill(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return change(() -> commands.deleteBill(id, reason, idempotencyKey), redirect, "/admin/bills", "账单已删除");
    }

    @PostMapping("/bills/{id}/restore")
    String restoreBill(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return change(() -> commands.restoreBill(id, reason, idempotencyKey), redirect, "/admin/bills", "账单已恢复");
    }

    @PostMapping("/ocr/{id}/retry")
    String retryOcr(@PathVariable String id, @RequestParam String idempotencyKey, RedirectAttributes redirect) {
        return change(() -> commands.retryOcr(id, idempotencyKey), redirect, "/admin/ocr", "OCR 任务已重新排队");
    }

    @GetMapping("/payments")
    String paymentList(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Long id, @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long billId,
            @RequestParam(required = false) PaymentStatus status, Model model) {
        PaymentFilter filter = new PaymentFilter(id, tenantId, billId, status);
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", PaymentStatus.values());
        model.addAttribute("page", queries.payments(filter, pageRequest(page, "paidAt")));
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
        return change(() -> commands.reversePayment(billId, paymentId, reason, idempotencyKey), redirect,
                "/admin/payments", "收款已冲正");
    }

    @GetMapping("/audit")
    String auditList(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(defaultValue = "") String action,
            @RequestParam(defaultValue = "") String aggregateType,
            @RequestParam(defaultValue = "") String aggregateId, Model model) {
        AuditFilter filter = new AuditFilter(tenantId, queryText(action), queryText(aggregateType),
                queryText(aggregateId));
        model.addAttribute("filter", filter);
        model.addAttribute("page", queries.audit(filter, pageRequest(page, "createdAt")));
        return "admin/audit";
    }

    private String change(Runnable operation, RedirectAttributes redirect, String target, String success) {
        try {
            operation.run();
            redirect.addFlashAttribute("message", success);
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", safeMessage(exception));
        }
        return "redirect:" + target;
    }

    private static String safeMessage(RuntimeException exception) {
        if (exception instanceof com.demeter.backend.common.error.BusinessRuleException
                || exception instanceof com.demeter.backend.common.error.ConflictException
                || exception instanceof com.demeter.backend.common.error.ResourceNotFoundException) {
            return exception.getMessage();
        }
        return "操作失败，请稍后重试并根据 requestId 查看日志";
    }

    private PageRequest pageRequest(int page) {
        return PageRequest.of(Math.min(Math.max(0, page), pagination.maxPage() - 1), pageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    private PageRequest pageRequest(int page, String sortProperty) {
        return PageRequest.of(Math.min(Math.max(0, page), pagination.maxPage() - 1), pageSize(),
                Sort.by(Sort.Direction.DESC, sortProperty).and(Sort.by(Sort.Direction.DESC, "id")));
    }

    private int pageSize() {
        return Math.min(30, pagination.maxSize());
    }

    private static String queryText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private static String adminCookiePath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        return (contextPath == null ? "" : contextPath) + "/admin";
    }
}
