package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.validator.MaxUtf8Bytes;
import com.abhinav.taskflow.common.validator.ValidPassword;
import jakarta.validation.constraints.*;

public record RegisterRequest(

        @NotBlank @Email @Size(max = 254)
        String email,

        @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]{1,48}[a-zA-Z0-9]$")
        String username,

        @NotBlank @Size(max = 100)
        String displayName,

        @ValidPassword
        String password
) {
    @Override
    public String toString() {
        return "RegisterRequest{" +
                "email='" + email + '\'' +
                ", username='" + username + '\'' +
                ", displayName='" + displayName + '\'' +
                '}';
    }
}
