package com.abhinav.taskflow.organization.invitation;

import com.abhinav.taskflow.common.tenant.OrganizationRole;

import java.time.Instant;

/** An invitation as the invitee sees it: no email (it's theirs), the organization's name (they can't look it up yet). */
public record MyInvitationResponse(Long id, Long organizationId, String organizationName, OrganizationRole role,
                                   String invitedBy, Instant expiresAt) {
}
