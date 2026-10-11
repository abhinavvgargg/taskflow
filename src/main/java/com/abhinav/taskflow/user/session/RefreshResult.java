package com.abhinav.taskflow.user.session;

import com.abhinav.taskflow.common.security.UserRole;

import java.time.Duration;

/**
 * What {@link SessionService#refresh} decided. Returned, never thrown: a rejection may have revoked a session and
 * recorded an event, and those writes must commit even though the request ends in a 401.
 */
public sealed interface RefreshResult {

    /** The token was rotated. Carries the successor (raw, for the cookie) and the claims for the new access token. */
    record Refreshed(Long sessionId, Long userAccountId, String username, UserRole role,
                     String refreshToken, Duration refreshTokenMaxAge) implements RefreshResult {

        @Override
        public String toString() {
            return "Refreshed{sessionId=" + sessionId + ", userAccountId=" + userAccountId
                    + ", refreshToken=<redacted>, refreshTokenMaxAge=" + refreshTokenMaxAge + '}';
        }
    }

    /** Refused. The reason is for logs and tests; the client always gets the same 401. */
    record Rejected(Reason reason) implements RefreshResult {

        /**
         * Superseded is the one rejection that must leave the cookie alone: the browser already holds the token the
         * concurrent winner just set, and clearing it would log the user out after a successful refresh.
         */
        public boolean clearCookie() {
            return reason != Reason.SUPERSEDED;
        }
    }

    enum Reason {
        /** Not 43 base64url characters (or absent): rejected without a query. */
        MALFORMED,
        /** Well formed, but no such hash. */
        UNKNOWN,
        /** Past its idle expiry. Not theft: the user simply came back too late. */
        EXPIRED,
        /** Consumed less than the grace window ago: a concurrent refresh with the same cookie won. Not theft. */
        SUPERSEDED,
        /** Consumed earlier than the grace window: theft. The session was revoked and the event recorded. */
        REUSED,
        /** The token was fine, but its session is revoked or past its absolute end (or the account is gone). */
        SESSION_ENDED
    }
}
