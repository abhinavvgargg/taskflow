package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.error.CommonErrorUtility;
import com.abhinav.taskflow.common.error.ResourceConflictException;
import com.abhinav.taskflow.common.util.Normalize;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserRegistrationService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional
    public UserAccountResponse registerUser (RegisterRequest registerRequest) {

        log.info("Registering {}", registerRequest);
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
        return UserAccountResponse.from(savedUser);
    }
}
