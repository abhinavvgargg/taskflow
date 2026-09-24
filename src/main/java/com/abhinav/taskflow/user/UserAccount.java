package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "user_accounts")
@Getter
public class UserAccount extends BaseEntity {

    private String email;
    private String username;
    private String displayName;
    private String timezone;
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private UserRole role;
    private Instant emailVerifiedAt;
    private int failedLoginAttempts;
    private Instant lockedUntil;
    private Instant passwordChangedAt;

    protected UserAccount() {}

    private UserAccount(String email, String username, String displayName, String passwordHash, Instant passwordChangedAt, String timezone, UserRole role) {
        this.email = email;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.passwordChangedAt = passwordChangedAt;
        this.timezone = timezone;
        this.role = role;
    }

    public static UserAccount createUserAccount(String email, String username, String displayName, String passwordHash, Instant passwordChangedAt) {
        return new UserAccount(email, username, displayName, passwordHash, passwordChangedAt, "UTC", UserRole.USER);
    }

    public void markEmailVerified(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public void changePassword(String newPasswordHash, Instant passwordChangedAt) {
        this.passwordHash = newPasswordHash;
        this.passwordChangedAt = passwordChangedAt;
    }

    private void setRole(UserRole role) {
        this.role = role;
    }

    private void setTimezone(String timezone) {
        this.timezone = timezone;
    }
}
