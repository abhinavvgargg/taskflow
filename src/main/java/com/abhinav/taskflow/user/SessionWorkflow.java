package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.error.RequestForbiddenException;
import com.abhinav.taskflow.common.security.AccessTokenIssuer;
import com.abhinav.taskflow.common.security.AllowedOrigins;
import com.abhinav.taskflow.common.security.IssuedAccessToken;
import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.session.RefreshResult;
import com.abhinav.taskflow.user.session.SessionErrorCode;
import com.abhinav.taskflow.user.session.SessionService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * The cookie endpoints' orchestration, deliberately OUTSIDE any transaction: SessionService.refresh commits its
 * decision (including a reuse revocation and its event) first, and only then is the result turned into a response.
 * §2.4's logout joins this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionWorkflow {

    private final AllowedOrigins allowedOrigins;
    private final SessionService sessionService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final Clock clock;

    public RefreshOutcome refresh(@Nullable String originHeader, @Nullable String rawRefreshToken, ClientInfo clientInfo) {
        // Before the cookie is even looked at: a disallowed page must not consume (and so burn) the user's token
        requireAllowedOrigin(originHeader);

        // One clock reading: the rotation's timestamps and the new access token's iat are the same instant
        Instant now = clock.instant();

        RefreshResult result = sessionService.refresh(rawRefreshToken, clientInfo, now);

        return switch (result) {
            case RefreshResult.Rejected rejected -> {
                log.debug("Refresh rejected [reason={}]", rejected.reason());
                yield new RefreshOutcome.Denied(rejected.clearCookie());
            }
            case RefreshResult.Refreshed refreshed -> {
                IssuedAccessToken accessToken = accessTokenIssuer.issue(refreshed.userAccountId(), refreshed.username(),
                        refreshed.role().name(), refreshed.sessionId(), now);
                yield new RefreshOutcome.Granted(accessToken, refreshed.refreshToken(), refreshed.refreshTokenMaxAge());
            }
        };
    }

    private void requireAllowedOrigin(@Nullable String originHeader) {
        if (!allowedOrigins.permits(originHeader)) {
            throw new RequestForbiddenException(SessionErrorCode.ORIGIN_NOT_ALLOWED, "Requests from this origin are not allowed.");
        }
    }
}
