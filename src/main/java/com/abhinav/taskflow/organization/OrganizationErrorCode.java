package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
public enum OrganizationErrorCode implements ErrorCode
{
    ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Organization not found"),
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
