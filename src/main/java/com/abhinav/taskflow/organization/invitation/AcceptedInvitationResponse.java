package com.abhinav.taskflow.organization.invitation;

import com.abhinav.taskflow.common.tenant.OrganizationRole;

/** Where accepting landed the caller: the client navigates straight into the organization. */
public record AcceptedInvitationResponse(Long organizationId, OrganizationRole role) {
}
