package com.abhinav.taskflow.user.session;

import com.abhinav.taskflow.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
public enum SessionErrorCode implements ErrorCode {

    /** Every refresh failure, whatever the reason: no oracle telling a thief "that one was rotated 3 s ago". */
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    /** A cookie endpoint called from a browser page on an origin we don't serve. */
    ORIGIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "Origin not allowed");

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
