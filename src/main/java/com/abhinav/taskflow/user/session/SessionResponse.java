package com.abhinav.taskflow.user.session;

import java.time.Instant;

/**
 * One entry of "my sessions" ({@code GET /api/v1/users/me/sessions}): enough for a person to recognise a device
 * ("Firefox on the laptop, last active an hour ago") and decide whether to end it. {@code current} marks the session
 * making the request, so the user doesn't log out the device they're holding by mistake.
 */
public record SessionResponse(Long id,
                              Instant createdAt,
                              Instant lastRefreshedAt,
                              Instant expiresAt,
                              String ipAddress,
                              String userAgent,
                              boolean current) {

    static SessionResponse from(SessionSummary summary, Long currentSessionId) {
        // getHostAddress() gives the plain literal ("203.0.113.7"), never "hostname/address" or a DNS lookup
        String ip = summary.ipAddress() == null ? null : summary.ipAddress().getHostAddress();
        return new SessionResponse(summary.id(), summary.createdAt(), summary.lastRefreshedAt(), summary.expiresAt(),
                ip, summary.userAgent(), summary.id().equals(currentSessionId));
    }

    @Override
    public String toString() {
        return "SessionResponse{id=" + id + ", lastRefreshedAt=" + lastRefreshedAt + ", current=" + current + '}';
    }
}
