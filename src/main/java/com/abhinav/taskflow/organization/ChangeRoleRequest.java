package com.abhinav.taskflow.organization;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ChangeRoleRequest(@NotNull @Pattern(regexp = "ADMIN|MEMBER") String role) {
}
