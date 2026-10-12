package com.abhinav.taskflow.user.session;

import com.abhinav.taskflow.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
public enum SessionErrorCode implements ErrorCode {

    /** Every refresh failure, whatever the reason: no oracle telling a thief "that one was rotated 3 s ago". */
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    /** A cookie endpoint called from a browser page on an origin we don't serve. */
    ORIGIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "Origin not allowed"),
    /** Revoke-one found nothing to revoke: unknown id, someone else's session, or one that already ended. Deliberately
     *  one answer for all three: session ids come from a guessable sequence, so "exists but not yours" would leak. */
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Session not found");

    private final HttpStatus status;
    private final String title;

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
