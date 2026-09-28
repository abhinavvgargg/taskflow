package com.abhinav.taskflow.user;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "taskflow.security.lockout")
public record LockoutProperties(

        @Positive(message = "maxFailedAttempts value must be at least 1")
        int maxFailedAttempts,

        @NotNull
        Duration duration
) {
    public LockoutProperties {
        if (duration != null && !duration.isPositive()) {
            throw new IllegalArgumentException("duration must be positive");
        }
    }
}
