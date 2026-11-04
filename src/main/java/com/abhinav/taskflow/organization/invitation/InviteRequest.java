package com.abhinav.taskflow.organization.invitation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// "role" is a String, not the enum: OWNER, GOD and admin all get the same 400 VALIDATION_FAILED with field: role
public record InviteRequest(

        @NotBlank @Email @Size(max = 254)
        String email,

        @NotNull @Pattern(regexp = "ADMIN|MEMBER")
        String role
) {
}
