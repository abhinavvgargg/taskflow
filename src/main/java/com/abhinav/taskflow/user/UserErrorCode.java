package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
public enum UserErrorCode implements ErrorCode {

    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email is already registered."),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "Username is already taken."),
    INVALID_TOKEN(HttpStatus.BAD_REQUEST, "Token is invalid"),
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "Authentication failed"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email is not verified"),
    PASSWORD_UNCHANGED(HttpStatus.BAD_REQUEST, "Password was not changed"),
    CURRENT_PASSWORD_INCORRECT(HttpStatus.BAD_REQUEST, "Current password is incorrect");

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
