package com.abhinav.taskflow.organization.invitation;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "taskflow.organizations")
public record InvitationProperties(

        @NotNull
        Duration invitationTtl
) {
    public InvitationProperties {
        if (invitationTtl != null && !invitationTtl.isPositive()) {
            throw new IllegalArgumentException("invitationTtl must be positive");
        }
    }
}
