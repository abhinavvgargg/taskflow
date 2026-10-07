package com.abhinav.taskflow.user.session;

import java.net.InetAddress;
import java.time.Instant;

public record SessionSummary(Long id,
                             Instant createdAt,
                             Instant lastRefreshedAt,
                             Instant expiresAt,
                             InetAddress ipAddress,
                             String userAgent) {

    @Override
    public String toString() {
        return "SessionSummary{id=" + id + ", lastRefreshedAt=" + lastRefreshedAt + '}';
    }
}