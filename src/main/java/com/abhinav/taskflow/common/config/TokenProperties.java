package com.abhinav.taskflow.common.config;

import com.abhinav.taskflow.user.token.TokenPurpose;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "taskflow.security.tokens")
public record TokenProperties(

        @NotNull
        Duration emailVerificationTtl,

        @NotNull
        Duration passwordResetTtl
) {
        public TokenProperties {
                if (emailVerificationTtl != null && !emailVerificationTtl.isPositive()) {
                        throw new IllegalArgumentException("emailVerificationTtl must be positive");
                }
        }

        public Duration ttl(TokenPurpose purpose) {
                return switch (purpose) {
                        case EMAIL_VERIFICATION -> emailVerificationTtl;
                        case PASSWORD_RESET -> passwordResetTtl;
                };
        }
}
