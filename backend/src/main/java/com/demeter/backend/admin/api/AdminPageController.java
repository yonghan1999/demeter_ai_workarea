package com.demeter.backend.admin.api;

import com.demeter.backend.admin.application.AdminDashboardService;
import com.demeter.backend.admin.security.AdminSessionService;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/admin")
public class AdminPageController {
    private final AdminSessionService sessions;
    private final AdminDashboardService dashboard;

    public AdminPageController(AdminSessionService sessions, AdminDashboardService dashboard) {
        this.sessions = sessions;
        this.dashboard = dashboard;
    }

    @GetMapping("/login")
    String login(Model model) {
        model.addAttribute("enabled", sessions.enabled());
        return "admin/login";
    }

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
        if (!sessions.verifyAccessToken(accessToken)) {
            return "redirect:/admin/login?error";
        }
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
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (AdminSessionService.COOKIE_NAME.equals(cookie.getName())) {
                    sessions.revoke(cookie.getValue());
                }
            }
        }
        ResponseCookie expired = ResponseCookie.from(AdminSessionService.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .path(adminCookiePath(request))
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", expired.toString());
        response.setHeader("Cache-Control", "no-store");
        return "redirect:/admin/login";
    }

    @GetMapping
    String home(Model model) {
        model.addAttribute("dashboard", dashboard.dashboard());
        return "admin/dashboard";
    }

    private static String adminCookiePath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        return (contextPath == null ? "" : contextPath) + "/admin";
    }
}
