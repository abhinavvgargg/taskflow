package com.abhinav.taskflow.user.token;

import com.abhinav.taskflow.common.persistence.BaseEntity;
import com.abhinav.taskflow.user.UserAccount;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "user_tokens")
public class UserToken extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private TokenPurpose purpose;

    @Column(nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant consumedAt;

    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false, updatable = false)
    @Getter
    private UserAccount userAccount;

    protected UserToken() {}

    public UserToken(UserAccount userAccount, TokenPurpose purpose, String tokenHash, Instant expiresAt) {
        this.userAccount = userAccount;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }
}