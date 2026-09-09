package com.demeter.backend.admin.api;

import com.demeter.backend.admin.application.AdminQueryFilters.AuditFilter;
import com.demeter.backend.admin.application.AdminQueryService;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/admin/audit")
public class AdminAuditPageController {
    private final AdminQueryService queries;
    private final AdminPageRequestFactory pages;

    public AdminAuditPageController(AdminQueryService queries, AdminPageRequestFactory pages) {
        this.queries = queries;
        this.pages = pages;
    }

    @GetMapping
    String list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(defaultValue = "") String action,
            @RequestParam(defaultValue = "") String aggregateType,
            @RequestParam(defaultValue = "") String aggregateId, Model model) {
        AuditFilter filter = new AuditFilter(
                tenantId,
                pages.queryText(action),
                pages.queryText(aggregateType),
                pages.queryText(aggregateId));
        model.addAttribute("filter", filter);
        model.addAttribute("page", queries.audit(filter, pages.descending(page, "createdAt")));
        return "admin/audit";
    }
}
