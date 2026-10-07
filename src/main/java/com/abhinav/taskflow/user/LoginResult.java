package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.security.IssuedAccessToken;
import com.abhinav.taskflow.user.session.StartedSession;

public record LoginResult(IssuedAccessToken accessToken, StartedSession session, UserAccountResponse user) {

    @Override
    public String toString() {
        return "LoginResult{accessToken=<redacted>, session=" + session + ", userId=" + user.id() + '}';
    }
}