package com.abhinav.taskflow.common.tenant;

import com.abhinav.taskflow.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;

/** The tenant boundary's errors: raised by the gate (404) and by {@link TenantContext#requireAtLeast} (403). */
@AllArgsConstructor
public enum TenantErrorCode implements ErrorCode {

    // One code for "doesn't exist" AND "you're not a member": ids are guessable, so the two must look the same
    ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Organization not found"),
    // Only ever raised INSIDE an organization the caller belongs to: they already know it exists
    ORGANIZATION_ROLE_REQUIRED(HttpStatus.FORBIDDEN, "Organization role required");

    private final HttpStatus status;
    private final String title;

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
