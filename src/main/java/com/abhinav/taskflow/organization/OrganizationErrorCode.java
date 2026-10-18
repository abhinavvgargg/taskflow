package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;

// ORGANIZATION_NOT_FOUND lives in common.tenant.TenantErrorCode: the gate raises it, and one code has one home
@AllArgsConstructor
public enum OrganizationErrorCode implements ErrorCode
{
    DUPLICATE_SLUG(HttpStatus.CONFLICT, "Duplicate slug"),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "Member not found"),
    CANNOT_MANAGE_MEMBER(HttpStatus.FORBIDDEN, "Cannot manage member"),
    MEMBERSHIP_CHANGED(HttpStatus.CONFLICT, "Membership changed"),
    OWNER_CANNOT_LEAVE(HttpStatus.CONFLICT, "Owner can't leave"),
    CANNOT_TRANSFER_TO_SELF(HttpStatus.BAD_REQUEST, "Cannot transfer to self");

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
