package com.abhinav.taskflow.common.security;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * The browser origins TaskFlow serves. Read by §2.3's Origin check now, and by §2.5's CORS configuration.
 *
 * <p>Each entry is an exact origin, {@code scheme://host[:port]}: no wildcard, no path, no trailing slash. A browser's
 * Origin header never has a path or a trailing slash, so an entry with one would silently match nothing.
 */
@Validated
@ConfigurationProperties(prefix = "taskflow.security.cors")
public record CorsProperties(

        @NotEmpty
        List<@Pattern(regexp = "^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$",
                message = "must be an exact origin: scheme://host[:port], no wildcard, path or trailing slash") String> allowedOrigins
) {
}
