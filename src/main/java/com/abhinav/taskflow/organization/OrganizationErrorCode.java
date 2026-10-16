package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;

// ORGANIZATION_NOT_FOUND lives in common.tenant.TenantErrorCode: the gate raises it, and one code has one home
@AllArgsConstructor
public enum OrganizationErrorCode implements ErrorCode
{
    DUPLICATE_SLUG(HttpStatus.CONFLICT, "Duplicate slug");

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
