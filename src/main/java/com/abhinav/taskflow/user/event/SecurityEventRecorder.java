package com.abhinav.taskflow.user.event;

import com.abhinav.taskflow.common.web.ClientInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class SecurityEventRecorder {

    private final SecurityEventRepository securityEventRepository;
    private final Clock clock;

    @Transactional
    public void recordLoginSucceeded (Long userAccountId, ClientInfo clientInfo) {
        securityEventRepository.save(SecurityEvent.loginSucceeded(userAccountId, clientInfo.inetAddress(), clientInfo.userAgent(), clock.instant()));
    }

    @Transactional
    public void recordLoginFailed (Long userAccountId, ClientInfo clientInfo, LoginFailureReason reason) {
        securityEventRepository.save(SecurityEvent.loginFailed(userAccountId, clientInfo.inetAddress(), clientInfo.userAgent(), clock.instant(), reason));
    }

    @Transactional
    public void recordAccountLocked (Long userAccountId, ClientInfo clientInfo) {
        securityEventRepository.save(SecurityEvent.accountLocked(userAccountId, clientInfo.inetAddress(), clientInfo.userAgent(), clock.instant()));
    }

    @Transactional
    public void recordEmailVerified (Long userAccountId, ClientInfo clientInfo) {
        securityEventRepository.save(SecurityEvent.emailVerified(userAccountId, clientInfo.inetAddress(), clientInfo.userAgent(), clock.instant()));
    }

    @Transactional
    public void recordPasswordReset (Long userAccountId, ClientInfo clientInfo) {
        securityEventRepository.save(SecurityEvent.passwordReset(userAccountId, clientInfo.inetAddress(), clientInfo.userAgent(), clock.instant()));
    }

    @Transactional
    public void recordPasswordChanged (Long userAccountId, ClientInfo clientInfo) {
        securityEventRepository.save(SecurityEvent.passwordChanged(userAccountId, clientInfo.inetAddress(), clientInfo.userAgent(), clock.instant()));
    }
}
