package com.abhinav.taskflow.user.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.web.server.Cookie;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "taskflow.security.sessions")
public record SessionProperties(

        @NotNull
        Duration refreshTokenIdleTtl,

        @NotNull
        Duration absoluteTtl,

        @NotNull
        Duration reuseGrace,

        @Valid
        @NotNull
        SessionProperties.CookieSettings cookie
) {
    public SessionProperties {
        if (refreshTokenIdleTtl != null && !refreshTokenIdleTtl.isPositive()) {
            throw new IllegalArgumentException("refreshTokenIdleTtl must be positive");
        }

        if (absoluteTtl != null && !absoluteTtl.isPositive()) {
            throw new IllegalArgumentException("absoluteTtl must be positive");
        }

        if (reuseGrace != null && reuseGrace.isNegative()) {
            throw new IllegalArgumentException("reuseGrace must not be negative");
        }
    }

    public record CookieSettings(

            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "must contain only letters, digits, '_' and '-'")
            String name,

            @NotBlank
            @Pattern(regexp = "^/.*", message = "must start with '/'")
            String path,

            @DefaultValue("true")
            boolean secure,

            @NotNull
            Cookie.SameSite sameSite
    ) {
        public CookieSettings {
            // Browsers reject SameSite=None cookies that aren't Secure
            if (sameSite == Cookie.SameSite.NONE && !secure) {
                throw new IllegalArgumentException("cookie.sameSite NONE requires cookie.secure true");
            }
        }
    }
}
