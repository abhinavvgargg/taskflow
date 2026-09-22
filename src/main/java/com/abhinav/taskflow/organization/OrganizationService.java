package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.error.ResourceConflictException;
import com.abhinav.taskflow.common.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;

    @Transactional
    public OrganizationResponseDto createOrganization(OrganizationRequestDto organizationRequestDto)
    {
        if (organizationRepository.existsOrganizationBySlug(organizationRequestDto.slug()))
        {
            throw new ResourceConflictException(OrganizationErrorCode.DUPLICATE_SLUG, "Organization with slug %s already exists".formatted(organizationRequestDto.slug())).with("slug", organizationRequestDto.slug());
        }
        Organization organization = organizationRepository.save(orgRequestToOrgMapper(organizationRequestDto));
        return OrganizationResponseDto.from(organization);
    }

    @Transactional(readOnly = true)
    public OrganizationResponseDto findOrganizationById (Long id)
    {
        Organization organization = organizationRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(OrganizationErrorCode.ORGANIZATION_NOT_FOUND, "No organization found with id %d".formatted(id)).with("id", id));
        return OrganizationResponseDto.from(organization);
    }

    @Transactional(readOnly = true)
    public Page<OrganizationResponseDto> findAllOrganizations (Pageable pageable)
    {
        return organizationRepository.findAll(pageable).map(OrganizationResponseDto::from);
    }

    private Organization orgRequestToOrgMapper(OrganizationRequestDto organizationRequestDto)
    {
        Organization organization = new Organization();
        organization.setName(organizationRequestDto.name());
        organization.setSlug(organizationRequestDto.slug());
        return organization;
    }
}
