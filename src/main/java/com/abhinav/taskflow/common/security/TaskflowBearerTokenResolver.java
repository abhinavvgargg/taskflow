package com.abhinav.taskflow.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring's default resolver (Authorization header only, strict "Bearer <token>" pattern, several tokens → 400),
 * except that it sees no token at all under /api/v1/auth/.
 *
 * <p>Why: authentication runs before authorization, even on permitAll paths. Clients attach their access token
 * to every request, so without this rule an expired token would turn login, refresh and logout into 401s:
 * exactly the calls a client with an expired token needs.
 */
public class TaskflowBearerTokenResolver implements BearerTokenResolver {

    private static final RequestMatcher AUTH_ENDPOINTS = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/**");

    private final DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        return AUTH_ENDPOINTS.matches(request) ? null : delegate.resolve(request);
    }
}
