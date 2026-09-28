package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.error.LoginFailedException;
import com.abhinav.taskflow.common.security.TaskflowPrincipal;
import com.abhinav.taskflow.common.util.Normalize;
import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.event.LoginFailureReason;
import com.abhinav.taskflow.user.event.SecurityEventRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT transactional: a failed authentication must not roll back the failure counter
 * (LoginAttemptService) or the LOGIN_FAILED event recorded below. Each record commits on its own.
 */
@Service
@RequiredArgsConstructor
public class LoginService {

    private static final String AUTHENTICATION_FAILED_DETAIL = "Invalid email or password. Sign-in pauses after repeated failures.";

    private final AuthenticationManager authenticationManager;
    private final UserAccountRepository userAccountRepository;
    private final SecurityEventRecorder securityEventRecorder;

    public UserAccountResponse login (LoginRequest loginRequest, ClientInfo clientInfo) {

        String email = Normalize.normalizeEmail(loginRequest.email());
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.unauthenticated(email, loginRequest.password());
        // Carries the IP to the failure listener, the same way BasicAuthenticationFilter does for Basic requests
        authentication.setDetails(new WebAuthenticationDetails(clientInfo.ipAddress(), null));

        Authentication authResult;
        try {
            authResult = authenticationManager.authenticate(authentication);
        }
        catch (BadCredentialsException e) {
            recordLoginFailed(email, clientInfo, LoginFailureReason.BAD_CREDENTIALS);
            throw new LoginFailedException(UserErrorCode.AUTHENTICATION_FAILED, AUTHENTICATION_FAILED_DETAIL);
        }
        catch (LockedException e) {
            recordLoginFailed(email, clientInfo, LoginFailureReason.ACCOUNT_LOCKED);
            throw new LoginFailedException(UserErrorCode.AUTHENTICATION_FAILED, AUTHENTICATION_FAILED_DETAIL);
        }
        catch (DisabledException e) {
            recordLoginFailed(email, clientInfo, LoginFailureReason.EMAIL_NOT_VERIFIED);
            throw new LoginFailedException(UserErrorCode.EMAIL_NOT_VERIFIED, "Email Not Verified");
        }
        // Any other AuthenticationException (e.g. the database is down) propagates unchanged: a 500, not a 401

        if (!(authResult.getPrincipal() instanceof TaskflowPrincipal taskflowPrincipal)) {
            throw new IllegalStateException("Principal is not a TaskflowPrincipal");
        }

        securityEventRecorder.recordLoginSucceeded(taskflowPrincipal.getId(), clientInfo);

        UserAccount userAccount = userAccountRepository.findById(taskflowPrincipal.getId()).orElseThrow(() -> new IllegalStateException("User not found"));

        return UserAccountResponse.from(userAccount);
    }

    /** Looks the account up only after authentication has failed, and only to record it; unknown emails record nothing. */
    private void recordLoginFailed (String email, ClientInfo clientInfo, LoginFailureReason reason) {
        userAccountRepository.findIdByEmail(email)
                .ifPresent(id -> securityEventRecorder.recordLoginFailed(id, clientInfo, reason));
    }
}
