package com.abhinav.taskflow.user.session;

import com.abhinav.taskflow.common.persistence.IdentifiedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.util.Assert;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Insert-only from Java, like UserSession: consuming is a conditional @Modifying UPDATE in RefreshTokenRepository.
@Entity
@Table(name = "refresh_tokens")
@Getter
public class RefreshToken extends IdentifiedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private UserSession session;

    @Column(nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(updatable = false)
    private Instant consumedAt;

    protected RefreshToken() {}

    private RefreshToken(UserSession session, String tokenHash, Instant now, Instant expiresAt) {
        this.session = session;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    // expiresAt arrives already capped: min(now + idle lifetime, session.expiresAt), computed by SessionService
    public static RefreshToken issue(UserSession session, String tokenHash, Instant now, Instant expiresAt) {
        Assert.notNull(session, "session must not be null");
        Assert.hasText(tokenHash, "tokenHash must not be blank");
        Assert.notNull(now, "now must not be null");
        Assert.notNull(expiresAt, "expiresAt must not be null");

        Instant created = now.truncatedTo(ChronoUnit.MICROS);
        Instant expires = expiresAt.truncatedTo(ChronoUnit.MICROS);
        Assert.isTrue(expires.isAfter(created), "expiresAt must be after now");
        Assert.isTrue(!expires.isAfter(session.getExpiresAt()), "expiresAt must not outlive the session");

        return new RefreshToken(session, tokenHash, created, expires);
    }

    @Override
    public String toString() {
        return "RefreshToken{id=" + getId()
                + ", expiresAt=" + expiresAt
                + ", consumed=" + (consumedAt != null) + '}';
    }
}