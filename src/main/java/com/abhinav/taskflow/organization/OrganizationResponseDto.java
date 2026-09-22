package com.abhinav.taskflow.organization;

import java.time.Instant;

public record OrganizationResponseDto(Long id, String name, String slug, String createdBy, Instant createdAt) {

    public static OrganizationResponseDto from(Organization organization)
    {
        return new OrganizationResponseDto(organization.getId(), organization.getName(), organization.getSlug(), organization.getCreatedBy(), organization.getCreatedAt());
    }
}
