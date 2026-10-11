package com.abhinav.taskflow.user.event;

public enum SecurityEventType {
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    ACCOUNT_LOCKED,
    EMAIL_VERIFIED,
    PASSWORD_RESET,
    PASSWORD_CHANGED,
    SESSION_REVOKED,
    ALL_SESSIONS_REVOKED,
    REFRESH_TOKEN_REUSED
}
