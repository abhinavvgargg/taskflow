package com.abhinav.taskflow.user.session;

import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.token.TokenCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final UserSessionRepository userSessionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenCodec tokenCodec;
    private final SessionProperties sessionProperties;

    /** Session and first refresh token commit together, in their own transaction. Only the hash is stored. */
    @Transactional
    public StartedSession start(Long userAccountId, ClientInfo clientInfo, Instant now) {
        UserSession session = userSessionRepository.save(
                UserSession.start(userAccountId, clientInfo, now, sessionProperties.absoluteTtl()));

        String rawToken = tokenCodec.generate();
        Instant expiresAt = refreshTokenExpiry(now, sessionProperties.refreshTokenIdleTtl(), session.getExpiresAt());
        RefreshToken refreshToken = refreshTokenRepository.save(
                RefreshToken.issue(session, tokenCodec.hash(rawToken), now, expiresAt));

        return new StartedSession(session.getId(), rawToken,
                Duration.between(refreshToken.getCreatedAt(), refreshToken.getExpiresAt()));
    }

    /** The earlier of now + idle lifetime and the session's absolute end. Reused by refresh in §2.3. */
    static Instant refreshTokenExpiry(Instant now, Duration idleTtl, Instant sessionExpiresAt) {
        Instant idleExpiry = now.plus(idleTtl);
        return idleExpiry.isBefore(sessionExpiresAt) ? idleExpiry : sessionExpiresAt;
    }
}