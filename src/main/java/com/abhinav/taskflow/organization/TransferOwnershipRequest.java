package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.validator.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TransferOwnershipRequest(@NotNull Long userId, @NotBlank @MaxUtf8Bytes(72) String currentPassword) {

    @Override
    public String toString() {
        return "TransferOwnershipRequest{" +
                "userId=" + userId +
                ", currentPassword=<redacted>}";
    }
}
