package com.demeter.backend.admin.api;

import com.demeter.backend.admin.application.AdminQueryFilters.TenantFilter;
import com.demeter.backend.admin.application.AdminQueryFilters.UserFilter;
import com.demeter.backend.admin.application.AdminQueryService;
import com.demeter.backend.admin.application.AdminTenantCommandService;
import com.demeter.backend.admin.application.AdminUserCommandService;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.identity.domain.TenantStatus;
import com.demeter.backend.identity.domain.UserStatus;
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
public class AdminIdentityPageController {
    private final AdminQueryService queries;
    private final AdminTenantCommandService tenantCommands;
    private final AdminUserCommandService userCommands;
    private final AdminPageRequestFactory pages;
    private final AdminOperationFeedback feedback;

    public AdminIdentityPageController(AdminQueryService queries, AdminTenantCommandService tenantCommands,
            AdminUserCommandService userCommands,
            AdminPageRequestFactory pages, AdminOperationFeedback feedback) {
        this.queries = queries;
        this.tenantCommands = tenantCommands;
        this.userCommands = userCommands;
        this.pages = pages;
        this.feedback = feedback;
    }

    @GetMapping("/tenants")
    String tenantList(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) TenantStatus status, Model model) {
        TenantFilter filter = new TenantFilter(pages.queryText(keyword), status);
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", TenantStatus.values());
        model.addAttribute("page", queries.tenants(filter, pages.descending(page, "createdAt")));
        return "admin/tenants";
    }

    @PostMapping("/tenants/{id}/suspend")
    String suspendTenant(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return feedback.execute(() -> tenantCommands.suspend(id, reason, idempotencyKey), redirect,
                "/admin/tenants", "租户已暂停");
    }

    @PostMapping("/tenants/{id}/activate")
    String activateTenant(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return feedback.execute(() -> tenantCommands.activate(id, reason, idempotencyKey), redirect,
                "/admin/tenants", "租户已恢复");
    }

    @PostMapping("/tenants/{id}/revoke-sessions")
    String revokeTenantSessions(@PathVariable long id, @RequestParam String reason,
            @RequestParam String idempotencyKey, RedirectAttributes redirect) {
        return feedback.execute(() -> tenantCommands.revokeSessions(id, reason, idempotencyKey), redirect,
                "/admin/tenants", "租户全部会话已撤销");
    }

    @GetMapping("/users")
    String userList(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Long id, @RequestParam(required = false) Long tenantId,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) UserStatus status, Model model) {
        UserFilter filter = new UserFilter(id, tenantId, pages.queryText(keyword), status);
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", UserStatus.values());
        model.addAttribute("page", queries.users(filter, pages.descending(page, "createdAt")));
        return "admin/users";
    }

    @PostMapping("/users/{id}/disable")
    String disableUser(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return feedback.execute(() -> userCommands.disable(id, reason, idempotencyKey), redirect,
                "/admin/users", "用户已禁用");
    }

    @PostMapping("/users/{id}/enable")
    String enableUser(@PathVariable long id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return feedback.execute(() -> userCommands.enable(id, reason, idempotencyKey), redirect,
                "/admin/users", "用户已启用");
    }

    @PostMapping("/users/{id}/revoke-sessions")
    String revokeUserSessions(@PathVariable long id, @RequestParam String reason,
            @RequestParam String idempotencyKey, RedirectAttributes redirect) {
        return feedback.execute(() -> userCommands.revokeSessions(id, reason, idempotencyKey), redirect,
                "/admin/users", "用户会话已撤销");
    }
}
