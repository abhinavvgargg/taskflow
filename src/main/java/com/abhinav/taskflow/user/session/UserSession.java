package com.abhinav.taskflow.user.session;

import com.abhinav.taskflow.common.persistence.IdentifiedEntity;
import com.abhinav.taskflow.common.web.ClientInfo;
import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.util.Assert;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Insert-only from Java: every later change is a conditional @Modifying UPDATE in UserSessionRepository.
// updatable = false keeps an accidental entity flush from writing a stale row back.
@Entity
@Table(name = "user_sessions")
@Getter
public class UserSession extends IdentifiedEntity {

    @Column(nullable = false, updatable = false)
    private Long userAccountId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant lastRefreshedAt;

    @Column(updatable = false)
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 32)
    private SessionRevokeReason revokeReason;

    @Column(updatable = false)
    private InetAddress ipAddress;

    @Column(updatable = false, length = ClientInfo.MAX_USER_AGENT_LENGTH)
    private String userAgent;

    protected UserSession() {}

    private UserSession(Long userAccountId, InetAddress ipAddress, String userAgent, Instant now, Instant expiresAt) {
        this.userAccountId = userAccountId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.createdAt = now;
        this.lastRefreshedAt = now;
        this.expiresAt = expiresAt;
    }

    public static UserSession start(Long userAccountId, ClientInfo clientInfo, Instant now, Duration absoluteTtl) {
        Assert.notNull(userAccountId, "userAccountId must not be null");
        Assert.notNull(clientInfo, "clientInfo must not be null");
        Assert.notNull(now, "now must not be null");
        Assert.isTrue(absoluteTtl != null && absoluteTtl.isPositive(), "absoluteTtl must be positive");

        // Postgres keeps microseconds; truncating makes the in-memory values equal what the row holds
        Instant start = now.truncatedTo(ChronoUnit.MICROS);
        return new UserSession(userAccountId, clientInfo.inetAddress(), clientInfo.userAgent(), start, start.plus(absoluteTtl));
    }

    public boolean isLive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    @Override
    public String toString() {
        return "UserSession{id=" + getId()
                + ", userAccountId=" + userAccountId
                + ", expiresAt=" + expiresAt
                + ", revokeReason=" + revokeReason + '}';
    }
}