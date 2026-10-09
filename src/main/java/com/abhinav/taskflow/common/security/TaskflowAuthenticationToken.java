package com.abhinav.taskflow.common.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * The Authentication stored in the SecurityContext for a request whose bearer token was accepted.
 * Its principal is an {@link AuthenticatedUser}; its one authority is {@code ROLE_<role>}.
 */
public class TaskflowAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser principal;

    public TaskflowAuthenticationToken(AuthenticatedUser principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        this.principal = principal;
        super.setAuthenticated(true);
    }

    /** The raw token isn't kept: nothing to leak through a log line or a serialized context. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.username();
    }

    /** Only the constructor may mark it authenticated; anyone else can only revoke that. */
    @Override
    public void setAuthenticated(boolean authenticated) {
        if (authenticated) {
            throw new IllegalArgumentException("Cannot mark this token as authenticated; use the constructor");
        }
        super.setAuthenticated(false);
    }
}
