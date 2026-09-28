package com.abhinav.taskflow.user.event;

import com.abhinav.taskflow.common.web.ClientInfo;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SecurityEventRecorder {

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4_LITERAL = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");

    private final SecurityEventRepository securityEventRepository;
    private final Clock clock;

    @Transactional
    public void recordLoginSucceeded (Long userAccountId, ClientInfo clientInfo) {
        securityEventRepository.save(SecurityEvent.loginSucceeded(userAccountId, ipOf(clientInfo), clientInfo.userAgent(), clock.instant()));
    }

    @Transactional
    public void recordLoginFailed (Long userAccountId, ClientInfo clientInfo, LoginFailureReason reason) {
        securityEventRepository.save(SecurityEvent.loginFailed(userAccountId, ipOf(clientInfo), clientInfo.userAgent(), clock.instant(), reason));
    }

    @Transactional
    public void recordAccountLocked (Long userAccountId, ClientInfo clientInfo) {
        securityEventRepository.save(SecurityEvent.accountLocked(userAccountId, ipOf(clientInfo), clientInfo.userAgent(), clock.instant()));
    }

    @Transactional
    public void recordEmailVerified (Long userAccountId, ClientInfo clientInfo) {
        securityEventRepository.save(SecurityEvent.emailVerified(userAccountId, ipOf(clientInfo), clientInfo.userAgent(), clock.instant()));
    }

    @Transactional
    public void recordPasswordReset (Long userAccountId, ClientInfo clientInfo) {
        securityEventRepository.save(SecurityEvent.passwordReset(userAccountId, ipOf(clientInfo), clientInfo.userAgent(), clock.instant()));
    }

    @Transactional
    public void recordPasswordChanged (Long userAccountId, ClientInfo clientInfo) {
        securityEventRepository.save(SecurityEvent.passwordChanged(userAccountId, ipOf(clientInfo), clientInfo.userAgent(), clock.instant()));
    }

    private static InetAddress ipOf(ClientInfo client) {
        return toInetAddress(client.ipAddress());
    }

    static @Nullable InetAddress toInetAddress(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String literal = value.strip();
        boolean ipv6 = literal.indexOf(':') >= 0;
        if (!ipv6 && !IPV4_LITERAL.matcher(literal).matches()) {
            return null;
        }
        try {
            InetAddress parsed = InetAddress.getByName(literal);
            return InetAddress.getByAddress(parsed.getAddress());
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
