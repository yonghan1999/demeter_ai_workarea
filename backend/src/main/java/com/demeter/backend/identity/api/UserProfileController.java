package com.demeter.backend.identity.api;

import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.identity.application.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/api/v1/users/me")
public class UserProfileController {

    private final UserProfileService service;

    public UserProfileController(UserProfileService service) {
        this.service = service;
    }

    @GetMapping
    UserProfileResponse get() {
        return service.get();
    }

    @PutMapping("/nickname")
    UserProfileResponse updateNickname(@Valid @RequestBody UpdateNicknameRequest request) {
        return service.updateNickname(request);
    }
}
