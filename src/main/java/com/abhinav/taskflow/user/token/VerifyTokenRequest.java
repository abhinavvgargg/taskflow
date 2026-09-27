package com.abhinav.taskflow.user.token;

import jakarta.validation.constraints.NotBlank;

public record VerifyTokenRequest(@NotBlank String token) {

    @Override
    public String toString() {
        return "VerifyTokenRequest[token=<redacted>]";
    }
}
