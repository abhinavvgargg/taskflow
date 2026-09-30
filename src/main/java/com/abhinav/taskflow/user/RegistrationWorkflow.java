package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.error.CommonErrorUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Deliberately NOT transactional: each call lets the transactional service commit first, and only then sends
 * the email, so no email ever goes out for data that was rolled back (the phantom email).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegistrationWorkflow {

    /** The partial unique index allowing one active token per user and purpose (V3). */
    private static final String ONE_ACTIVE_TOKEN_CONSTRAINT = "uk_user_tokens_active";

    private final UserRegistrationService userRegistrationService;
    private final AccountEmails accountEmails;

    public UserAccountResponse registerAndSendEmail (RegisterRequest registerRequest) {

        RegistrationResult result = userRegistrationService.registerUser(registerRequest);   // committed on return
        UserAccountResponse account = result.userAccountResponse();

        accountEmails.sendVerificationEmail(account.id(), account.email(), result.issuedToken());
        return account;
    }

    /** Always completes normally, so the endpoint answers 202 whether or not an email was sent. */
    public void resendVerificationEmail (String email) {

        Optional<VerificationToSend> toSend;
        try {
            toSend = userRegistrationService.resend(email);   // committed (or rolled back) on return
        } catch (DataIntegrityViolationException e) {
            // Caught HERE, outside the transaction, not inside resend(): by the time the violation leaves
            // saveAndFlush, Spring has marked the transaction rollback-only, and swallowing it in there would
            // make the commit fail with UnexpectedRollbackException (a 500).
            if (ONE_ACTIVE_TOKEN_CONSTRAINT.equalsIgnoreCase(CommonErrorUtility.constraintNameOf(e))) {
                // A concurrent resend for the same account won the race; it sends the email. Nothing to do.
                log.debug("Concurrent verification resend; the other request sends the email");
                return;
            }
            throw e;
        }

        toSend.ifPresent(v -> accountEmails.sendVerificationEmail(v.accountId(), v.email(), v.issuedToken()));
    }
}
