package com.abhinav.taskflow.organization.invitation;

import com.abhinav.taskflow.common.tenant.OrganizationRole;

import java.time.Instant;

/** An open invitation as the organization's admins see it. invitedBy is the inviter's username (created_by). */
public record InvitationResponse(Long id, String email, OrganizationRole role, String invitedBy, Instant createdAt, Instant expiresAt) {

    public static InvitationResponse from(OrganizationInvitation invitation) {
        return new InvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getRole(),
                invitation.getCreatedBy(), invitation.getCreatedAt(), invitation.getExpiresAt());
    }
}
