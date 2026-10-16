package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.tenant.OrganizationRole;

import java.time.Instant;

/**
 * One member of an organization, as other members see them. No email: members don't need each other's addresses
 * (data minimisation). Built directly by the JPQL query (constructor expression), so no entity is loaded.
 */
public record MemberResponseDto(Long userId, String username, String displayName, OrganizationRole role, Instant joinedAt) {
}
