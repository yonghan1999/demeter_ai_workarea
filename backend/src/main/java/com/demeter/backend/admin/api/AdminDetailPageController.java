package com.demeter.backend.admin.api;

import com.demeter.backend.admin.application.AdminDetailService;
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
public class AdminDetailPageController {
    private final AdminDetailService details;

    public AdminDetailPageController(AdminDetailService details) {
        this.details = details;
    }

    @GetMapping("/bills/{id}")
    String bill(@PathVariable long id, Model model) {
        try {
            model.addAttribute("detail", details.bill(id));
            return "admin/bill-detail";
        } catch (ResourceNotFoundException exception) {
            model.addAttribute("error", "账单不存在或已被移除");
            return "admin/not-found";
        }
    }
}
