package com.abhinav.taskflow.user;

import com.abhinav.taskflow.user.token.IssuedToken;

public record ResetToSend(Long accountId, String email, IssuedToken issuedToken) {

    @Override
    public String toString() {
        return "ResetToSend[accountId=" + accountId + ", email=<redacted>, issuedToken=<redacted>]";
    }
}
