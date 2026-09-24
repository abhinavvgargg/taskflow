package com.abhinav.taskflow.common.config;

import com.abhinav.taskflow.common.security.TaskflowPrincipal;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;

public class AuditAwareImpl implements AuditorAware<String> {

    @Override
    public Optional<String> getCurrentAuditor() {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.of("system");
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof TaskflowPrincipal) {
            String username = ((TaskflowPrincipal) principal).getAppUsername();
            return Optional.ofNullable(username).or(() -> Optional.of("system"));
        }
        return Optional.of("system");
    }
}
