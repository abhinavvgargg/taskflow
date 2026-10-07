package com.abhinav.taskflow.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "taskflow.security.jwt")
public record JwtProperties(

        @NotBlank
        String issuer,

        @NotBlank
        String audience,

        @NotNull
        Duration accessTokenTtl,

        @NotNull
        Duration clockSkew,

        String keyId,

        String privateKey,

        String publicKey
) {
    public JwtProperties {
        if (accessTokenTtl != null && !accessTokenTtl.isPositive()) {
            throw new IllegalArgumentException("accessTokenTtl must be positive");
        }

        if (clockSkew != null && clockSkew.isNegative()) {
            throw new IllegalArgumentException("clockSkew must not be negative");
        }

        keyId = blankToNull(keyId);
        privateKey = blankToNull(privateKey);
        publicKey = blankToNull(publicKey);

        int configured = (keyId != null ? 1 : 0) + (privateKey != null ? 1 : 0) + (publicKey != null ? 1 : 0);
        if (configured != 0 && configured != 3) {
            throw new IllegalArgumentException("keyId, privateKey and publicKey must be set together: all three or none");
        }
    }

    public boolean hasConfiguredKey() {
        return keyId != null;
    }

    @Override
    public String toString() {
        return "JwtProperties[issuer=%s, audience=%s, accessTokenTtl=%s, clockSkew=%s, keyId=%s, privateKey=%s, publicKey=%s]"
                .formatted(issuer, audience, accessTokenTtl, clockSkew, keyId,
                        privateKey == null ? null : "[REDACTED]",
                        publicKey == null ? null : "[set]");
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
