package com.abhinav.taskflow.common.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "taskflow.security.tokens")
public record TokenProperties(

        @NotNull
        Duration emailVerificationTtl
) {
        public TokenProperties {
                if (!emailVerificationTtl().isPositive()) {
                        throw new IllegalArgumentException("emailVerificationTtl must be positive");
                }
        }
}
