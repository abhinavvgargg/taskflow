package com.abhinav.taskflow.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrganizationRequestDto (

        @NotBlank @Size(max = 100, min = 3)
        String name,

        @NotBlank @Size(max = 63, min = 3) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$")
        String slug
) {
}
