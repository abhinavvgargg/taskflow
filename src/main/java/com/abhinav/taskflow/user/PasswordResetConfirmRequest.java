package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.validator.ValidPassword;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetConfirmRequest(

        @NotBlank
        String token,

        @ValidPassword
        String newPassword
) {
    @Override
    public String toString() {
        return "PasswordResetConfirmRequest{}";
    }
}
