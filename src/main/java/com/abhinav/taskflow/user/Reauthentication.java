package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.error.ResourceInvalidException;
import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.web.ClientInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * "Sudo mode": proves the account's owner is at the keyboard NOW, for actions a stolen access token must not be able
 * to perform (changing the password, transferring an organization). One implementation, so every caller counts a wrong
 * password toward lockout the same way a login does.
 *
 * <p>Call it OUTSIDE any transaction: bcrypt takes ~100 ms on purpose, and must not hold a pooled connection meanwhile.
 */
@Component
@RequiredArgsConstructor
public class Reauthentication {

    private final AuthenticationManager authenticationManager;
    private final UserAccountRepository userAccountRepository;

    /** Returns normally when {@code password} is the caller's current password; otherwise 400 CURRENT_PASSWORD_INCORRECT. */
    public void confirmPassword(AuthenticatedUser caller, String password, ClientInfo clientInfo) {

        // The bearer principal has no email (the token carries none, on purpose). Load it by id; never add it to the token.
        String email = userAccountRepository.findEmailById(caller.id())
                .orElseThrow(() -> new IllegalStateException("Authenticated account no longer exists"));

        // Through the manager, like a login: a wrong current password counts toward lockout
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.unauthenticated(email, password);
        authentication.setDetails(new WebAuthenticationDetails(clientInfo.ipAddress(), null));

        try {
            authenticationManager.authenticate(authentication);
        } catch (BadCredentialsException | LockedException e) {
            // A locked account gets the same answer: a separate one would confirm the lock to whoever holds the token
            throw new ResourceInvalidException(UserErrorCode.CURRENT_PASSWORD_INCORRECT, "Invalid current password.")
                    .with("field", "currentPassword");
        }
    }
}
