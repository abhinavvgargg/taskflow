package com.abhinav.taskflow.common.config;

import com.abhinav.taskflow.common.security.AuthenticatedUser;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Who goes into created_by / updated_by: the caller's app username, read from the bearer principal.
 * It follows the principal TYPE: if that type ever changes again, this silently falls back to "system",
 * and only the created_by assertion in OrganizationApiIntegrationTest notices.
 */
public class AuditAwareImpl implements AuditorAware<String> {

    @Override
    public Optional<String> getCurrentAuditor() {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.of("system");
        }

        if (authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.ofNullable(user.username()).or(() -> Optional.of("system"));
        }
        return Optional.of("system");
    }
}
