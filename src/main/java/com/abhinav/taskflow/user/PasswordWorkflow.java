package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.error.CommonErrorUtility;
import com.abhinav.taskflow.common.error.ResourceInvalidException;
import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.web.ClientInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordWorkflow {

    private final PasswordService passwordService;
    private final AccountEmails accountEmails;
    private final Reauthentication reauthentication;

    public void requestReset(String email) {

        Optional<ResetToSend> resetToSend;
        try {
            resetToSend = passwordService.requestReset(email);
        } catch (DataIntegrityViolationException e) {
            if ("uk_user_tokens_active".equalsIgnoreCase(CommonErrorUtility.constraintNameOf(e))) {
                log.debug("Concurrent password reset resend; the other request sends the email");
                return;
            }
            throw e;
        }
        resetToSend.ifPresent(toSend -> accountEmails.sendPasswordResetEmail(toSend.accountId(), toSend.email(), toSend.issuedToken()));
    }

    public void confirmReset(String token, String newPassword, ClientInfo clientInfo) {
        AccountContact accountContact = passwordService.confirmReset(token, newPassword, clientInfo);
        accountEmails.sendPasswordChangedEmail(accountContact.accountId(), accountContact.email());
    }

    public void changePassword(AuthenticatedUser authenticatedUser, ChangePasswordRequest changePasswordRequest, ClientInfo clientInfo) {
        if (changePasswordRequest.newPassword().equals(changePasswordRequest.currentPassword())) {
            throw new ResourceInvalidException(UserErrorCode.PASSWORD_UNCHANGED, "Old and new passwords cannot be same.").with("field", "newPassword");
        }

        reauthentication.confirmPassword(authenticatedUser, changePasswordRequest.currentPassword(), clientInfo);

        AccountContact accountContact = passwordService.applyChange(authenticatedUser.id(), authenticatedUser.sessionId(), changePasswordRequest.newPassword(), clientInfo);
        accountEmails.sendPasswordChangedEmail(accountContact.accountId(), accountContact.email());
    }
}
