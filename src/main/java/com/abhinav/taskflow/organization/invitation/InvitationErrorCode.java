package com.abhinav.taskflow.organization.invitation;

import com.abhinav.taskflow.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
public enum InvitationErrorCode implements ErrorCode {

    // Unknown, another organization's, not yours, expired, answered or revoked: one code, so none can be told apart
    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Invitation not found"),
    // Invite: the address belongs to a member. Accept: the caller is one already
    ALREADY_MEMBER(HttpStatus.CONFLICT, "Already a member"),
    // Only ever raised inside an organization the caller belongs to (an admin inviting an ADMIN)
    ROLE_NOT_GRANTABLE(HttpStatus.FORBIDDEN, "Role not grantable");

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
