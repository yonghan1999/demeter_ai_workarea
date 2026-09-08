package com.demeter.backend.admin.security;

import static com.demeter.backend.common.web.RequestPaths.applicationPath;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminAccessFilter extends OncePerRequestFilter {
    private final AdminSessionService sessions;

    public AdminAccessFilter(AdminSessionService sessions) { this.sessions = sessions; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !applicationPath(request).startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = applicationPath(request);
        if (!sessions.enabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String token = cookie(request);
        if (sessions.isActive(token)) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(new AdminPrincipal("configured-admin"), null, List.of()));
        } else if (!path.equals("/admin/login") && !path.equals("/admin/admin.css")
                && !path.equals("/admin/admin.js") && !path.startsWith("/admin/assets/")) {
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect(request.getContextPath() + "/admin/login");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            response.setHeader("Cache-Control", "no-store");
            SecurityContextHolder.clearContext();
        }
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (AdminSessionService.COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

}
