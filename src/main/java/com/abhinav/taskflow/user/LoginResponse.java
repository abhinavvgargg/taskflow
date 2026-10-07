package com.abhinav.taskflow.user;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserAccountResponse user) {

    private static final String BEARER = "Bearer";

    public static LoginResponse from(LoginResult result) {
        return new LoginResponse(result.accessToken().value(), BEARER,
                result.accessToken().expiresInSeconds(), result.user());
    }

    @Override
    public String toString() {
        return "LoginResponse{accessToken=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + '}';
    }
}