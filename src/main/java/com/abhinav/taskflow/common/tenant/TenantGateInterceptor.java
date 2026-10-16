package com.abhinav.taskflow.common.tenant;

import com.abhinav.taskflow.common.error.ResourceNotFoundException;
import com.abhinav.taskflow.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Optional;

/**
 * The tenant gate: may this caller act in organization {orgId} at all? Runs on every request under
 * /api/v1/organizations/{orgId} (patterns in {@link TenantWebConfig}), after the security chain (the principal is
 * known) and after handler mapping (the path variables are parsed). Exceptions thrown here go through
 * {@code @RestControllerAdvice}, so a refusal has the same ProblemDetail body as any other error.
 *
 * <p>Non-member and non-existent organization get the SAME 404: ids come from one global sequence and are guessable,
 * so anything else would confirm which organizations exist.
 */
@Component
@RequiredArgsConstructor
public class TenantGateInterceptor implements HandlerInterceptor {

    /** The one name every org-scoped controller uses for the organization's path variable. */
    public static final String ORG_ID_VARIABLE = "orgId";

    private final MembershipLookup membershipLookup;
    private final TenantContext tenantContext;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Only OUR controller methods can reach tenant data, so only they are gated (and every one of them is). Anything
        // else under these paths runs no code of ours: a path no controller maps (Spring's resource handler and its
        // 404), and Spring's own HandlerMethods, like the answer to a plain OPTIONS, which is built when no mapping
        // matches and so carries no path variables (fail-closed would turn it into a 500).
        if (!isControllerMethod(handler)) {
            return true;
        }

        long organizationId = parseOrganizationId(orgIdVariable(request));
        Optional<OrganizationRole> role = membershipLookup.roleOf(organizationId, callerId());

        tenantContext.enter(organizationId, role.orElseThrow(TenantGateInterceptor::organizationNotFound));
        return true;
    }

    // A method of a @Controller bean (@RestController included): an OPTIONS mapping we write ourselves stays gated
    private static boolean isControllerMethod(Object handler) {
        return handler instanceof HandlerMethod handlerMethod
                && AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), Controller.class);
    }

    // Fail CLOSED: a controller under /organizations/* that names the variable {id} or {organizationId} is a bug, and
    // skipping the check for it would let any user into any organization through that endpoint
    @SuppressWarnings("unchecked")
    private static String orgIdVariable(HttpServletRequest request) {
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String orgId = variables == null ? null : variables.get(ORG_ID_VARIABLE);
        if (orgId == null) {
            throw new IllegalStateException("Org-scoped handler for " + request.getRequestURI()
                    + " has no {" + ORG_ID_VARIABLE + "} path variable");
        }
        return orgId;
    }

    // Non-numeric is just another id that names no organization: the same 404, not a 400 that a probe could tell apart
    private static long parseOrganizationId(String orgId) {
        try {
            return Long.parseLong(orgId);
        } catch (NumberFormatException e) {
            throw organizationNotFound();
        }
    }

    // The security chain already required authentication on /api/v1/**; any principal other than ours is a bug (500)
    private static long callerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.id();
        }
        throw new IllegalStateException("The tenant gate needs an AuthenticatedUser principal");
    }

    // No id in the detail or the properties: the body must be identical for every miss (the path is in "instance")
    private static ResourceNotFoundException organizationNotFound() {
        return new ResourceNotFoundException(TenantErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found");
    }
}
