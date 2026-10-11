package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.security.IssuedAccessToken;

import java.time.Duration;

/** What the refresh endpoint answers: the controller turns it into a 200 or a 401, with the right Set-Cookie. */
public sealed interface RefreshOutcome {

    record Granted(IssuedAccessToken accessToken, String refreshToken, Duration refreshTokenMaxAge) implements RefreshOutcome {

        @Override
        public String toString() {
            return "Granted{accessToken=<redacted>, refreshToken=<redacted>, refreshTokenMaxAge=" + refreshTokenMaxAge + '}';
        }
    }

    record Denied(boolean clearCookie) implements RefreshOutcome {
    }
}
