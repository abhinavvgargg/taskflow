package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.error.CommonErrorUtility;
import com.abhinav.taskflow.common.error.ResourceInvalidException;
import com.abhinav.taskflow.common.security.TaskflowPrincipal;
import com.abhinav.taskflow.common.web.ClientInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordWorkflow {

    private final PasswordService passwordService;
    private final AccountEmails accountEmails;
    private final AuthenticationManager authenticationManager;

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

    public void changePassword(TaskflowPrincipal taskflowPrincipal, ChangePasswordRequest changePasswordRequest, ClientInfo clientInfo) {
        if (changePasswordRequest.newPassword().equals(changePasswordRequest.currentPassword())) {
            throw new ResourceInvalidException(UserErrorCode.PASSWORD_UNCHANGED, "Old and new passwords cannot be same.").with("field", "newPassword").with("field", "currentPassword");
        }

        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.unauthenticated(taskflowPrincipal.getUsername(), changePasswordRequest.currentPassword());
        authentication.setDetails(new WebAuthenticationDetails(clientInfo.ipAddress(), null));

        try {
            authenticationManager.authenticate(authentication);
        }
        catch (Exception e) {
            if (e instanceof BadCredentialsException || e instanceof LockedException) {
                throw new ResourceInvalidException(UserErrorCode.CURRENT_PASSWORD_INCORRECT, "Invalid current password.");
            }
            throw e;
        }

        AccountContact accountContact = passwordService.applyChange(taskflowPrincipal.getId(), changePasswordRequest.newPassword(),  clientInfo);
        accountEmails.sendPasswordChangedEmail(accountContact.accountId(), accountContact.email());
    }
}
