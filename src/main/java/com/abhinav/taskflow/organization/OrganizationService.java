package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.error.ResourceConflictException;
import com.abhinav.taskflow.common.error.ResourceNotFoundException;
import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.tenant.TenantContext;
import com.abhinav.taskflow.common.tenant.TenantErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final TenantContext tenantContext;

    /**
     * The organization and its OWNER membership, in one transaction. V7's deferred trigger checks at COMMIT that the
     * organization has an owner, so forgetting the second insert fails the whole request (409), never half-creates.
     */
    @Transactional
    public OrganizationResponseDto create(OrganizationRequestDto request, AuthenticatedUser caller)
    {
        // Checked first for a friendly 409 with the slug; uk_organizations_slug stays the real guard under a race
        if (organizationRepository.existsOrganizationBySlug(request.slug()))
        {
            throw new ResourceConflictException(OrganizationErrorCode.DUPLICATE_SLUG, "Organization with slug %s already exists".formatted(request.slug())).with("slug", request.slug());
        }
        Organization organization = organizationRepository.save(Organization.create(request.name(), request.slug()));
        membershipRepository.save(OrganizationMembership.of(organization.getId(), caller.id(), OrganizationRole.OWNER));
        return OrganizationResponseDto.from(organization, OrganizationRole.OWNER);
    }

    /** Only organizations the caller is a member of, each with the caller's role. Not org-scoped: no tenant needed. */
    @Transactional(readOnly = true)
    public Page<OrganizationResponseDto> listMine(AuthenticatedUser caller, Pageable pageable)
    {
        return organizationRepository.findMine(caller.id(), pageable);
    }

    /** The organization the gate let this request into. Its id comes from TenantContext only, never from the path. */
    @Transactional(readOnly = true)
    public OrganizationResponseDto get()
    {
        long organizationId = tenantContext.organizationId();
        // The gate found a membership, and memberships cascade with their organization, so a miss here means it was
        // deleted in between: the same 404 as any other miss
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException(TenantErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));
        return OrganizationResponseDto.from(organization, tenantContext.role());
    }
}
