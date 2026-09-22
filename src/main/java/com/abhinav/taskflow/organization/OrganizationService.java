package com.abhinav.taskflow.organization;

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
            throw new DuplicateSlugException("Organization already exists");
        }
        Organization organization = organizationRepository.save(orgRequestToOrgMapper(organizationRequestDto));
        return OrganizationResponseDto.from(organization);
    }

    @Transactional(readOnly = true)
    public OrganizationResponseDto findOrganizationById (Long id)
    {
        Organization organization = organizationRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organization", id));
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
