package com.demeter.backend.auth.api;

import com.demeter.backend.auth.application.AuthService;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/wechat/login")
    LoginResponse login(@Valid @RequestBody WechatLoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/logout")
    LogoutResponse logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim()
                : null;
        return authService.logout(token);
    }
}
