package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.error.RequestForbiddenException;
import com.abhinav.taskflow.common.error.ResourceNotFoundException;
import com.abhinav.taskflow.common.security.AccessTokenIssuer;
import com.abhinav.taskflow.common.security.AllowedOrigins;
import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.security.IssuedAccessToken;
import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.session.RefreshResult;
import com.abhinav.taskflow.user.session.SessionErrorCode;
import com.abhinav.taskflow.user.session.SessionResponse;
import com.abhinav.taskflow.user.session.SessionService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * The cookie endpoints' orchestration, deliberately OUTSIDE any transaction: SessionService.refresh commits its
 * decision (including a reuse revocation and its event) first, and only then is the result turned into a response.
 * Also holds logout and the session-list operations (§2.4), which follow the same shape.
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

    /**
     * Logout of this device, authenticated by the refresh COOKIE (it works even when the access token has expired).
     * Same Origin rule as refresh. Never fails for a bad, unknown or missing cookie: the controller always answers 204.
     */
    public void logout(@Nullable String originHeader, @Nullable String rawRefreshToken) {
        requireAllowedOrigin(originHeader);
        boolean revoked = sessionService.revokeByRefreshToken(rawRefreshToken, clock.instant());
        log.debug("Logout [sessionRevoked={}]", revoked);
    }

    /** The caller's live sessions, the current one marked. The owner always comes from the principal, never the URL. */
    public Page<SessionResponse> listSessions(AuthenticatedUser caller, Pageable pageable) {
        return sessionService.listLive(caller.id(), caller.sessionId(), pageable, clock.instant());
    }

    /**
     * Revokes one of the caller's sessions. "Nothing revoked" becomes a 404 for every cause alike (unknown id, someone
     * else's session, already ended), thrown here, after SessionService's transaction (which wrote nothing) is over.
     */
    public void revokeSession(AuthenticatedUser caller, Long sessionId, ClientInfo clientInfo) {
        if (!sessionService.revokeOne(caller.id(), sessionId, clientInfo, clock.instant())) {
            throw new ResourceNotFoundException(SessionErrorCode.SESSION_NOT_FOUND, "Session not found.");
        }
    }

    /** "Log out everywhere", this device included. */
    public void logOutEverywhere(AuthenticatedUser caller, ClientInfo clientInfo) {
        int revoked = sessionService.logOutEverywhere(caller.id(), clientInfo, clock.instant());
        log.debug("Logged out everywhere [sessionsRevoked={}]", revoked);
    }

    private void requireAllowedOrigin(@Nullable String originHeader) {
        if (!allowedOrigins.permits(originHeader)) {
            throw new RequestForbiddenException(SessionErrorCode.ORIGIN_NOT_ALLOWED, "Requests from this origin are not allowed.");
        }
    }
}
