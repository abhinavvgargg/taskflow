package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.tenant.OrganizationRole;

import java.time.Instant;

/** An organization as the caller sees it, with the caller's own role in it. */
public record OrganizationResponseDto(Long id, String name, String slug, OrganizationRole role, String createdBy, Instant createdAt) {

    public static OrganizationResponseDto from(Organization organization, OrganizationRole role)
    {
        return new OrganizationResponseDto(organization.getId(), organization.getName(), organization.getSlug(), role, organization.getCreatedBy(), organization.getCreatedAt());
    }
}
