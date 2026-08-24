package com.demeter.backend.security;

import com.demeter.backend.common.error.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class CurrentActor {

    public DemeterPrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof DemeterPrincipal principal)) {
            throw new UnauthorizedException("Authentication is required");
        }
        return principal;
    }

    public Optional<DemeterPrincipal> optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof DemeterPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }
}
