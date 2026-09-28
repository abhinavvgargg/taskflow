package com.abhinav.taskflow.user.event;

import com.abhinav.taskflow.common.persistence.IdentifiedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "security_events")
@Immutable
@Getter
public class SecurityEvent extends IdentifiedEntity {

    private Long userAccountId;

    @Enumerated(EnumType.STRING)
    private SecurityEventType eventType;

    @Enumerated(EnumType.STRING)
    private LoginFailureReason failureReason;

    private Instant occurredAt;

    private InetAddress ipAddress;

    private String userAgent;

    protected SecurityEvent() {}

    private SecurityEvent(SecurityEventType eventType, Long userAccountId, InetAddress ipAddress, String userAgent, LoginFailureReason failureReason, Instant occurredAt) {

        this.eventType = eventType;
        this.userAccountId = userAccountId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.failureReason = failureReason;
        this.occurredAt = occurredAt;
    }

    public static SecurityEvent loginSucceeded (Long userAccountId, InetAddress ipAddress, String userAgent, Instant occurredAt) {
        return new SecurityEvent(SecurityEventType.LOGIN_SUCCEEDED, userAccountId, ipAddress, userAgent, null, occurredAt);
    }

    public static SecurityEvent loginFailed (Long userAccountId, InetAddress ipAddress, String userAgent, Instant occurredAt, LoginFailureReason failureReason) {
        return new SecurityEvent(SecurityEventType.LOGIN_FAILED, userAccountId, ipAddress, userAgent, Objects.requireNonNull(failureReason), occurredAt);
    }

    public static SecurityEvent accountLocked (Long userAccountId, InetAddress ipAddress, String userAgent, Instant occurredAt) {
        return new SecurityEvent(SecurityEventType.ACCOUNT_LOCKED, userAccountId, ipAddress, userAgent, null, occurredAt);
    }

    public static SecurityEvent emailVerified (Long userAccountId, InetAddress ipAddress, String userAgent, Instant occurredAt) {
        return new SecurityEvent(SecurityEventType.EMAIL_VERIFIED, userAccountId, ipAddress, userAgent, null, occurredAt);
    }

    public static SecurityEvent passwordReset (Long userAccountId, InetAddress ipAddress, String userAgent, Instant occurredAt) {
        return new SecurityEvent(SecurityEventType.PASSWORD_RESET, userAccountId, ipAddress, userAgent, null, occurredAt);
    }

    public static SecurityEvent passwordChanged (Long userAccountId, InetAddress ipAddress, String userAgent, Instant occurredAt) {
        return new SecurityEvent(SecurityEventType.PASSWORD_CHANGED, userAccountId, ipAddress, userAgent, null, occurredAt);
    }

    @Override
    public String toString() {
        return "SecurityEvent{" +
                "eventType=" + eventType +
                '}';
    }
}
