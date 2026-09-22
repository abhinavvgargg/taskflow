package com.abhinav.taskflow.organization;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    Optional<Organization> findOrganizationBySlug(String slug);

    Boolean existsOrganizationBySlug(String slug);
}
