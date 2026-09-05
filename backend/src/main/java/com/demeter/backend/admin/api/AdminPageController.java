package com.demeter.backend.admin.api;

import com.demeter.backend.admin.application.AdminDashboardService;
import com.demeter.backend.admin.security.AdminSessionService;
import com.demeter.backend.admin.application.AdminQueryService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin")
public class AdminPageController {
    private final AdminSessionService sessions;
    private final AdminDashboardService dashboard;
    private final AdminQueryService queries;

    public AdminPageController(AdminSessionService sessions, AdminDashboardService dashboard,
            AdminQueryService queries) {
        this.sessions = sessions; this.dashboard = dashboard; this.queries = queries;
    }

    @GetMapping("/login")
    String login(Model model) { model.addAttribute("enabled", sessions.enabled()); return "admin/login"; }

    @PostMapping("/login")
    String login(@RequestParam String accessToken, HttpServletRequest request, HttpServletResponse response) {
        if (!sessions.verifyAccessToken(accessToken)) return "redirect:/admin/login?error";
        Cookie cookie = new Cookie(AdminSessionService.COOKIE_NAME, sessions.createSession());
        cookie.setHttpOnly(true); cookie.setSecure(request.isSecure()); cookie.setPath("/admin");
        cookie.setMaxAge((int) sessions.sessionTtlSeconds());
        response.addHeader("Set-Cookie", cookie + "; SameSite=Lax");
        response.setHeader("Cache-Control", "no-store");
        return "redirect:/admin";
    }

    @PostMapping("/logout")
    String logout(HttpServletRequest request, HttpServletResponse response) {
        if (request.getCookies() != null) for (Cookie c : request.getCookies())
            if (AdminSessionService.COOKIE_NAME.equals(c.getName())) sessions.revoke(c.getValue());
        Cookie expired = new Cookie(AdminSessionService.COOKIE_NAME, ""); expired.setMaxAge(0); expired.setPath("/admin");
        response.addHeader("Set-Cookie", expired + "; SameSite=Lax");
        response.setHeader("Cache-Control", "no-store");
        return "redirect:/admin/login";
    }

    @GetMapping
    String home(Model model) { model.addAttribute("dashboard", dashboard.dashboard()); return "admin/dashboard"; }

    @GetMapping("/tenants")
    String tenantList(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", queries.tenants(pageRequest(page)));
        return "admin/tenants";
    }

    @GetMapping("/users")
    String userList(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", queries.users(pageRequest(page)));
        return "admin/users";
    }

    @GetMapping("/bills")
    String billList(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", queries.bills(pageRequest(page, "updatedAt")));
        return "admin/bills";
    }

    @GetMapping("/ocr")
    String ocrList(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", queries.ocr(pageRequest(page, "createdAt")));
        return "admin/ocr";
    }

    @GetMapping("/audit")
    String auditList(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", queries.audit(pageRequest(page)));
        return "admin/audit";
    }

    private static PageRequest pageRequest(int page) {
        return PageRequest.of(Math.min(Math.max(0, page), 10_000), 30);
    }

    private static PageRequest pageRequest(int page, String sortProperty) {
        return PageRequest.of(Math.min(Math.max(0, page), 10_000), 30,
                Sort.by(Sort.Direction.DESC, sortProperty));
    }
}
