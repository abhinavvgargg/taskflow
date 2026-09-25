package com.abhinav.taskflow.user;

import java.time.Instant;

public record UserAccountResponse(Long id, String email, String username, String displayName, Boolean emailVerified, Instant createdAt) {

    public static UserAccountResponse from(UserAccount userAccount) {
        return new UserAccountResponse(userAccount.getId(), userAccount.getEmail(), userAccount.getUsername(), userAccount.getDisplayName(), userAccount.getEmailVerifiedAt() != null, userAccount.getCreatedAt());
    }
}
