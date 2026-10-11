package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.security.IssuedAccessToken;

/** The refresh body: like the login body, without the user (the client already has it). */
public record RefreshResponse(String accessToken, String tokenType, long expiresIn) {

    private static final String BEARER = "Bearer";

    public static RefreshResponse from(IssuedAccessToken accessToken) {
        return new RefreshResponse(accessToken.value(), BEARER, accessToken.expiresInSeconds());
    }

    @Override
    public String toString() {
        return "RefreshResponse{accessToken=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + '}';
    }
}
