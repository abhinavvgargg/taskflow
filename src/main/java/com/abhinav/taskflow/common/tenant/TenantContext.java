package com.abhinav.taskflow.common.tenant;

import com.abhinav.taskflow.common.error.RequestForbiddenException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * The organization this request acts in, and the caller's role there: filled once by {@link TenantGateInterceptor}
 * after the membership check, then read by services. The ONLY source of the current organization id: services never
 * take it from a path variable or a body, so the value a query uses is always the value the gate checked.
 *
 * <p>Request-scoped: singletons get a proxy that finds the current request's instance, and the instance is discarded
 * when the request ends, so a tenant can't be left behind on a pooled Tomcat thread (D5). Outside a request (a
 * {@code @Scheduled} job, an {@code @Async} thread) any call throws {@code ScopeNotActiveException}: loud, on purpose.
 * Not final and with a no-arg constructor, because the scoped proxy is a CGLIB subclass.
 */
@Component
@RequestScope
public class TenantContext {

    private Long organizationId;
    private OrganizationRole role;

    /** Called once per request, by the gate. A second call is a bug (two gates, or code re-entering): fail loudly. */
    public void enter(long organizationId, OrganizationRole role) {
        if (this.organizationId != null) {
            throw new IllegalStateException("A tenant was already entered for this request");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        this.organizationId = organizationId;
        this.role = role;
    }

    public boolean isEntered() {
        return organizationId != null;
    }

    /** A service reached without passing the gate is a bug, not a client error: 500. */
    public long organizationId() {
        requireEntered();
        return organizationId;
    }

    public OrganizationRole role() {
        requireEntered();
        return role;
    }

    /** 403 when the caller's role is below {@code required}. The caller is a member, so a 403 reveals nothing. */
    public void requireAtLeast(OrganizationRole required) {
        if (!role().isAtLeast(required)) {
            throw new RequestForbiddenException(TenantErrorCode.ORGANIZATION_ROLE_REQUIRED,
                    "This action requires the %s role or higher".formatted(required))
                    .with("requiredRole", required.name());
        }
    }

    private void requireEntered() {
        if (organizationId == null) {
            throw new IllegalStateException("No tenant entered: this code ran outside the organization gate");
        }
    }
}
