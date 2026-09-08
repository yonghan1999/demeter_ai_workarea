package com.demeter.backend.admin.api;

import com.demeter.backend.admin.application.AdminAuditDetailService;
import com.demeter.backend.common.error.ResourceNotFoundException;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/admin")
public class AdminAuditDetailPageController {
    private final AdminAuditDetailService details;

    public AdminAuditDetailPageController(AdminAuditDetailService details) {
        this.details = details;
    }

    @GetMapping("/audit/{id}")
    String audit(@PathVariable long id, Model model) {
        try {
            model.addAttribute("detail", details.find(id));
            return "admin/audit-detail";
        } catch (ResourceNotFoundException exception) {
            model.addAttribute("error", "审计记录不存在或已被移除");
            return "admin/not-found";
        }
    }
}
