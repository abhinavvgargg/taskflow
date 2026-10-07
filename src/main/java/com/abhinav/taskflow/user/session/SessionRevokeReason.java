package com.abhinav.taskflow.user.session;

public enum SessionRevokeReason {
    LOGOUT,
    LOGOUT_ALL,
    REVOKED_BY_USER,
    PASSWORD_CHANGED,
    PASSWORD_RESET,
    REFRESH_TOKEN_REUSE
}
