package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.config.FrontendProperties;
import com.abhinav.taskflow.common.error.CommonErrorUtility;
import com.abhinav.taskflow.common.mail.EmailMessage;
import com.abhinav.taskflow.common.mail.EmailSender;
import com.abhinav.taskflow.user.token.IssuedToken;
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
    private final EmailSender emailSender;
    private final FrontendProperties frontendProperties;

    public UserAccountResponse registerAndSendEmail (RegisterRequest registerRequest) {

        RegistrationResult result = userRegistrationService.registerUser(registerRequest);   // committed on return
        UserAccountResponse account = result.userAccountResponse();

        sendVerificationEmail(account.id(), account.email(), result.issuedToken());
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

        toSend.ifPresent(v -> sendVerificationEmail(v.accountId(), v.email(), v.issuedToken()));
    }

    /** The one place a verification link is built and sent. A failure is logged, never propagated. */
    private void sendVerificationEmail (Long accountId, String email, IssuedToken token) {
        String link = frontendProperties.frontendBaseUrl() + "/verify-email#token=" + token.value();
        try {
            emailSender.send(new EmailMessage(email, "Verify your email address", link));
        } catch (Exception e) {
            // The account id only: never the email (PII) or the link (it contains the token).
            log.error("Failed to send verification email for account id={}", accountId, e);
        }
    }
}
