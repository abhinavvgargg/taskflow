package com.abhinav.taskflow.user.session;

import java.time.Duration;

// Holds a raw 14-day credential: toString must never print it
public record StartedSession(Long sessionId, String refreshToken, Duration refreshTokenMaxAge) {

    @Override
    public String toString() {
        return "StartedSession{sessionId=" + sessionId + ", refreshToken=<redacted>, refreshTokenMaxAge=" + refreshTokenMaxAge + '}';
    }
}