package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.security.TaskflowPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthenticationEventsListener {

    private final LoginAttemptService loginAttemptService;

    @EventListener
    public void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        Authentication authentication = event.getAuthentication();
        loginAttemptService.recordFailure(authentication.getName(), remoteAddressOf(authentication));
    }

    /**
     * Only PASSWORD authentications (login, change-password) reset the counter: their principal is a TaskflowPrincipal.
     * A success event is also published on EVERY bearer request (principal: AuthenticatedUser). Don't drop this
     * type check: it would add a write per request, and the owner's own open tab would keep resetting the counter
     * while an attacker guesses the password, so lockout would never trigger.
     */
    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication().getPrincipal() instanceof TaskflowPrincipal taskflowPrincipal) {
            loginAttemptService.recordSuccess(taskflowPrincipal.getId());
        }
    }

    /** The socket address: LoginService and PasswordWorkflow set these details explicitly. Null if absent, never a placeholder. */
    private static String remoteAddressOf(Authentication authentication) {
        return authentication.getDetails() instanceof WebAuthenticationDetails details ? details.getRemoteAddress() : null;
    }
}
