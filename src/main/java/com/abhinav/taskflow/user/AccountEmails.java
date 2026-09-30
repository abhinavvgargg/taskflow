package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.config.FrontendProperties;
import com.abhinav.taskflow.common.mail.EmailMessage;
import com.abhinav.taskflow.common.mail.EmailSender;
import com.abhinav.taskflow.user.token.IssuedToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountEmails {

    private final EmailSender emailSender;
    private final FrontendProperties frontendProperties;

    public void sendVerificationEmail(Long accountId, String email, IssuedToken issuedToken) {
        String link = frontendProperties.frontendBaseUrl() + "/verify-email#token=" + issuedToken.value();
        try {
            emailSender.send(new EmailMessage(email, "Verify your email address", link));
        } catch (Exception e) {
            log.error("Failed to send verification email for account id={}", accountId, e);
        }
    }

    public void sendPasswordResetEmail(Long accountId, String email, IssuedToken issuedToken) {
        String link = frontendProperties.frontendBaseUrl() + "/reset-password#token=" + issuedToken.value();
        try {
            emailSender.send(new EmailMessage(email, "Reset your password", link));
        } catch (Exception e) {
            log.error("Failed to send password reset email for account id={}", accountId, e);
        }
    }

    public void sendPasswordChangedEmail(Long accountId, String email) {
        try {
            emailSender.send(new EmailMessage(email, "Your password was changed", "Your password was changed. If this wasn't you, reset it now."));
        } catch (Exception e) {
            log.error("Failed to send password changed email for account id={}", accountId, e);
        }
    }
}
