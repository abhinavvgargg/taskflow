package com.abhinav.taskflow.user.token;

import java.time.Instant;

public record IssuedToken(String value, Instant expiresAt) {

    @Override
    public String toString() {
        return "IssuedToken{value=<redacted> expiresAt=" + expiresAt + '}';
    }
}
