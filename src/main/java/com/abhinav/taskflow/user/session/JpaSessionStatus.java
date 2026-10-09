package com.abhinav.taskflow.user.session;

import com.abhinav.taskflow.common.security.SessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** One indexed query per request, selecting a boolean (no entity). No catch: a database error must surface as a 500. */
@Component
@RequiredArgsConstructor
class JpaSessionStatus implements SessionStatus {

    private final UserSessionRepository userSessionRepository;
    private final Clock clock;

    @Override
    public boolean isLive(long sessionId, long userAccountId) {
        return userSessionRepository.isLive(sessionId, userAccountId, clock.instant());
    }
}
