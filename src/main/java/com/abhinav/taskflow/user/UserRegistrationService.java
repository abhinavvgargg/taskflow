package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.error.CommonErrorUtility;
import com.abhinav.taskflow.common.error.ResourceConflictException;
import com.abhinav.taskflow.common.util.Normalize;
import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.event.SecurityEventRecorder;
import com.abhinav.taskflow.user.token.IssuedToken;
import com.abhinav.taskflow.user.token.TokenPurpose;
import com.abhinav.taskflow.user.token.UserToken;
import com.abhinav.taskflow.user.token.UserTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserRegistrationService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final UserTokenService userTokenService;
    private final SecurityEventRecorder securityEventRecorder;

    @Transactional
    public RegistrationResult registerUser (RegisterRequest registerRequest) {

        String normalizedEmail = Normalize.normalizeEmail(registerRequest.email());
        String normalizedUsername = Normalize.normalizeUsername(registerRequest.username());

        if (userAccountRepository.existsByEmail(normalizedEmail)) {
            throw new ResourceConflictException(UserErrorCode.EMAIL_ALREADY_REGISTERED, "Email already exists").with("field", "email");
        }
        if (userAccountRepository.existsByUsername(normalizedUsername)) {
            throw new ResourceConflictException(UserErrorCode.USERNAME_TAKEN, "Username is in use").with("field", "username");
        }

        UserAccount userAccount = UserAccount.createUserAccount(normalizedEmail, normalizedUsername,
                registerRequest.displayName().strip(), passwordEncoder.encode(registerRequest.password()), Instant.now(clock));

        UserAccount savedUser;

        try {
            savedUser = userAccountRepository.saveAndFlush(userAccount);
            log.info("User account id = {} registered successfully", userAccount.getId());
        }
        catch (DataIntegrityViolationException e) {

            String constraintName = CommonErrorUtility.constraintNameOf(e);

            if (constraintName != null && !constraintName.isBlank()) {

                if (constraintName.equalsIgnoreCase("uk_user_accounts_email")) {
                    throw new ResourceConflictException(UserErrorCode.EMAIL_ALREADY_REGISTERED, "Email already exists").with("field", "email");
                }

                if (constraintName.equalsIgnoreCase("uk_user_accounts_username")) {
                    throw new ResourceConflictException(UserErrorCode.USERNAME_TAKEN, "Username is in use").with("field", "username");
                }
            }
            throw e;
        }

        IssuedToken issuedToken = userTokenService.issue(userAccount, TokenPurpose.EMAIL_VERIFICATION);

        return new RegistrationResult(UserAccountResponse.from(savedUser), issuedToken);
    }

    @Transactional
    public void verify (String rawToken, ClientInfo clientInfo) {
        UserToken token = userTokenService.consume(rawToken,  TokenPurpose.EMAIL_VERIFICATION);
        UserAccount account = token.getUserAccount();
        account.markEmailVerified(clock.instant());
        // Same transaction: EMAIL_VERIFIED exists exactly when the verification commits
        securityEventRecorder.recordEmailVerified(account.getId(), clientInfo);
    }

    @Transactional
    public Optional<VerificationToSend> resend (String rawEmail) {

        // Unknown or already-verified email: nothing to send. The caller answers 202 either way.
        // No try/catch here on purpose: a constraint violation from issue() has already marked this
        // transaction rollback-only, so it must propagate. RegistrationWorkflow handles it outside the transaction.
        return userAccountRepository.findByEmail(Normalize.normalizeEmail(rawEmail))
                .filter(account -> account.getEmailVerifiedAt() == null)
                .map(account -> new VerificationToSend(
                        account.getId(),
                        account.getEmail(),
                        userTokenService.issue(account, TokenPurpose.EMAIL_VERIFICATION)));
    }
}
