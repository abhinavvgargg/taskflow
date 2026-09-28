package com.abhinav.taskflow.user;

import com.abhinav.taskflow.user.event.LoginFailureReason;
import com.abhinav.taskflow.user.event.SecurityEvent;
import com.abhinav.taskflow.user.event.SecurityEventType;
import jakarta.annotation.Nullable;

import java.net.InetAddress;
import java.time.Instant;

public record LoginHistoryResponse(
        Instant occurredAt,
        SecurityEventType eventType,
        @Nullable LoginFailureReason failureReason,
        @Nullable String ipAddress,
        @Nullable String userAgent
) {
    static LoginHistoryResponse from (SecurityEvent securityEvent) {
        InetAddress ipAddress = securityEvent.getIpAddress();
        return new LoginHistoryResponse(
                securityEvent.getOccurredAt(),
                securityEvent.getEventType(),
                securityEvent.getFailureReason(),
                ipAddress == null ? null : ipAddress.getHostAddress(),
                securityEvent.getUserAgent()
        );
    }
}
