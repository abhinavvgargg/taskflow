package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.validator.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(

        @NotBlank @Size(max = 254)
        String email,

        @NotBlank @MaxUtf8Bytes(72)
        String password
) {
    @Override
    public String toString() {
        return "LoginRequest{email=<hidden>, password=<hidden>}";
    }
}
