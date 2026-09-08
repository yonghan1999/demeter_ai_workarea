package com.demeter.backend.admin.api;

import com.demeter.backend.admin.application.AdminMaintenanceQueryService;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/admin/maintenance")
public class AdminMaintenancePageController {
    private final AdminMaintenanceQueryService maintenance;

    public AdminMaintenancePageController(AdminMaintenanceQueryService maintenance) {
        this.maintenance = maintenance;
    }

    @GetMapping
    String index(Model model) {
        model.addAttribute("runs", maintenance.recentRuns());
        return "admin/maintenance";
    }
}
