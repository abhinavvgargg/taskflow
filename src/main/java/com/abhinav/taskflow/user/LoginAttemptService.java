package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.util.Normalize;
import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.event.SecurityEventRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final UserAccountRepository userAccountRepository;
    private final Clock clock;
    private final LockoutProperties lockoutProperties;
    private final SecurityEventRecorder securityEventRecorder;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String rawEmail, String ipAddress) {

        if (rawEmail == null || rawEmail.isBlank()) {
            return;
        }

        Optional<Long> found = userAccountRepository.findIdByEmail(Normalize.normalizeEmail(rawEmail));

        if (found.isEmpty()) {
            return;
        }

        Long id = found.get();

        userAccountRepository.incrementFailedLoginAttempts(id);

        Instant now = clock.instant();

        int locked = userAccountRepository.lockIfThresholdReached(id, lockoutProperties.maxFailedAttempts(), now.plus(lockoutProperties.duration()));

        if (locked == 1) {
            // Joins this transaction, so the event commits exactly when the lock does. No User-Agent here:
            // the authentication details only carry the address.
            securityEventRecorder.recordAccountLocked(id, new ClientInfo(ipAddress, null));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long id) {
        userAccountRepository.resetFailedLoginAttempts(id);
    }
}
