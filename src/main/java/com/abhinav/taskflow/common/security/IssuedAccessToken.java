package com.abhinav.taskflow.common.security;

import java.time.Duration;
import java.time.Instant;

public record IssuedAccessToken(String value, Instant issuedAt, Instant expiresAt) {

    public long expiresInSeconds() {
        return Duration.between(issuedAt, expiresAt).toSeconds();
    }

    @Override
    public String toString() {
        return "IssuedAccessToken{value=<redacted> issuedAt=" + issuedAt + " expiresAt=" + expiresAt + '}';
    }
}
