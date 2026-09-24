package com.abhinav.taskflow.common.config;

import com.abhinav.taskflow.common.security.TaskflowPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for "who goes into created_by / updated_by". Each test puts an Authentication into
 * SecurityContextHolder by hand, exactly as Spring Security does during a real request.
 */
class AuditAwareImplTest {

    private final AuditAwareImpl auditor = new AuditAwareImpl();

    @AfterEach
    void clearSecurityContext() {
        // SecurityContextHolder is a ThreadLocal and test threads are reused: without this, the next test
        // would inherit this test's user. Same lesson as MDC.remove in the correlation filter.
        SecurityContextHolder.clearContext();
    }

    @Test
    void taskflowPrincipal_recordsTheAppUsername_notTheEmail() {
        var principal = new TaskflowPrincipal(1L, "alice", "alice@example.com", "{bcrypt}hash",
                List.of(), true, true);
        authenticate(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));

        assertThat(auditor.getCurrentAuditor()).contains("alice");
    }

    @Test
    void anonymousRequest_recordsSystem_notAnonymousUser() {
        // AnonymousAuthenticationToken reports isAuthenticated() == true, which is the trap this guards.
        authenticate(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(auditor.getCurrentAuditor()).contains("system");
    }

    @Test
    void emptySecurityContext_recordsSystem() {
        // e.g. a scheduled job or a startup task: no request, no authentication at all
        assertThat(auditor.getCurrentAuditor()).contains("system");
    }

    @Test
    void someOtherPrincipalType_recordsSystem() {
        // e.g. Spring's own User, which @WithMockUser installs: it has no app username to record
        var springUser = User.withUsername("bob@example.com").password("x").roles("USER").build();
        authenticate(UsernamePasswordAuthenticationToken.authenticated(springUser, null, springUser.getAuthorities()));

        assertThat(auditor.getCurrentAuditor()).contains("system");
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
