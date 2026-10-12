package com.abhinav.taskflow.common.security;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * Which browser pages, on which origins, may call the API from JavaScript and READ the responses.
 *
 * <p>Two registrations, and their ORDER matters: {@link UrlBasedCorsConfigurationSource} returns the first pattern
 * that matches, so the narrower /api/v1/auth/** must come before /api/v1/**, or it would never be reached.
 * <ul>
 *   <li>/api/v1/auth/**: credentials allowed, because refresh and logout need the browser to send the refresh cookie
 *       on a cross-origin call (the frontend uses fetch(..., {credentials: 'include'})).</li>
 *   <li>/api/v1/**: no credentials. Those endpoints authenticate with a bearer header the script sets itself, so
 *       allowing cookies there would grant something nothing needs (least privilege).</li>
 * </ul>
 *
 * <p>Not a bean: TaskflowSecurityConfig passes the source to http.cors(...) explicitly. Spring Security's other way of
 * finding it, a bean named exactly "corsConfigurationSource", silently ignores a bean with any other name.
 */
final class TaskflowCorsConfiguration {

    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");
    // Content-Type is listed because application/json is not a "simple" content type: it triggers a preflight
    private static final List<String> REQUEST_HEADERS = List.of("Authorization", "Content-Type", "X-Correlation-Id");
    // Scripts can only read safelisted response headers unless they're exposed: the correlation id (for support),
    // Location (after a 201) and WWW-Authenticate (to tell "refresh and retry" from "log in again" on a 401)
    private static final List<String> EXPOSED_HEADERS = List.of("X-Correlation-Id", "Location", "WWW-Authenticate");
    private static final Duration PREFLIGHT_CACHE = Duration.ofHours(1);

    private TaskflowCorsConfiguration() {}

    static CorsConfigurationSource source(CorsProperties corsProperties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/auth/**", configuration(corsProperties, true));   // first: first match wins
        source.registerCorsConfiguration("/api/v1/**", configuration(corsProperties, false));
        return source;
    }

    private static CorsConfiguration configuration(CorsProperties corsProperties, boolean allowCredentials) {
        CorsConfiguration configuration = new CorsConfiguration();
        // Exact origins only (validated in CorsProperties): never "*", never patterns. With credentials, "*" is refused
        // by Spring anyway; reflecting any Origin would let every website read a logged-in user's responses.
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(METHODS);
        configuration.setAllowedHeaders(REQUEST_HEADERS);
        configuration.setExposedHeaders(EXPOSED_HEADERS);
        configuration.setAllowCredentials(allowCredentials);
        configuration.setMaxAge(PREFLIGHT_CACHE);
        return configuration;
    }
}
