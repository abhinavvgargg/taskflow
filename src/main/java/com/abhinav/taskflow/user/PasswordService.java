package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.util.Normalize;
import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.event.SecurityEventRecorder;
import com.abhinav.taskflow.user.token.TokenPurpose;
import com.abhinav.taskflow.user.token.UserToken;
import com.abhinav.taskflow.user.token.UserTokenRepository;
import com.abhinav.taskflow.user.token.UserTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PasswordService {

    private final UserAccountRepository userAccountRepository;
    private final UserTokenService userTokenService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final UserTokenRepository userTokenRepository;
    private final SecurityEventRecorder securityEventRecorder;

    @Transactional
    public Optional<ResetToSend> requestReset(String email) {

        return userAccountRepository.findByEmail(Normalize.normalizeEmail(email))
                .map(userAccount -> new ResetToSend(userAccount.getId(), userAccount.getEmail(), userTokenService.issue(userAccount, TokenPurpose.PASSWORD_RESET)));
    }

    @Transactional
    public AccountContact confirmReset(String rawToken, String newPassword, ClientInfo clientInfo) {

        UserToken token = userTokenService.consume(rawToken, TokenPurpose.PASSWORD_RESET);
        UserAccount account = token.getUserAccount();
        Instant now = clock.instant();

        boolean isEmailVerified = account.resetPassword(passwordEncoder.encode(newPassword), now);
        if (isEmailVerified) {
            userTokenRepository.revokeActive(account, TokenPurpose.EMAIL_VERIFICATION, now);
            securityEventRecorder.recordEmailVerified(account.getId(), clientInfo);
        }
        securityEventRecorder.recordPasswordReset(account.getId(), clientInfo);

        return new AccountContact(account.getId(), account.getEmail());
    }

    @Transactional
    public AccountContact applyChange(Long accountId, String newPassword, ClientInfo clientInfo) {
        Instant now = clock.instant();
        UserAccount userAccount = userAccountRepository.findById(accountId).orElseThrow(() -> new IllegalStateException("User account not found"));
        userAccount.changePassword(passwordEncoder.encode(newPassword), now);
        userTokenRepository.revokeActive(userAccount, TokenPurpose.PASSWORD_RESET, now);
        securityEventRecorder.recordPasswordChanged(userAccount.getId(), clientInfo);
        return new AccountContact(userAccount.getId(), userAccount.getEmail());
    }
}
