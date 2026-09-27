package com.abhinav.taskflow.user;

import com.abhinav.taskflow.user.token.IssuedToken;

/**
 * What the transactional service hands the workflow after committing a new verification token: who to send it
 * to, and the raw token. Never leaves the server.
 *
 * <p>{@code toString()} shows only the account id: the email is PII and the token is a credential.
 */
public record VerificationToSend(Long accountId, String email, IssuedToken issuedToken) {

    @Override
    public String toString() {
        return "VerificationToSend[accountId=" + accountId + ", email=<redacted>, issuedToken=<redacted>]";
    }
}
