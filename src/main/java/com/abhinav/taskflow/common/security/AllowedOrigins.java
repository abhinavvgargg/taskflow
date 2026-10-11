package com.abhinav.taskflow.common.security;

import jakarta.annotation.Nullable;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Answers "may a page on this origin drive an endpoint that acts on the refresh cookie?" (refresh, logout).
 *
 * <p>Why it exists next to SameSite=Strict: SameSite stops other SITES, but app.example.com and evil.example.com are the
 * same site, so the browser still attaches the cookie for a request a sibling subdomain triggers. Browsers always send
 * Origin on a cross-origin POST, and a page can't forge it, so checking it closes that gap.
 */
@Component
public class AllowedOrigins {

    private final Set<String> origins;

    public AllowedOrigins(CorsProperties corsProperties) {
        this.origins = Set.copyOf(corsProperties.allowedOrigins());
    }

    /**
     * No Origin header → allowed: curl, mobile apps and server-side clients don't send one, and CSRF needs a browser,
     * which always does. A present header must match exactly (including the literal "null" sandboxed pages send).
     */
    public boolean permits(@Nullable String originHeader) {
        return originHeader == null || origins.contains(originHeader);
    }
}
