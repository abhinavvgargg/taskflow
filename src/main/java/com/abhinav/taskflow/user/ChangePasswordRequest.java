package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.validator.MaxUtf8Bytes;
import com.abhinav.taskflow.common.validator.ValidPassword;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(

        @NotBlank @MaxUtf8Bytes(72)
        String currentPassword,

        @ValidPassword
        String newPassword
) {
    @Override
    public String toString() {
        return "ChangePasswordRequest{}";
    }
}
