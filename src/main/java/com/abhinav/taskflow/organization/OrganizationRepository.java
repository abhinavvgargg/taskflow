package com.abhinav.taskflow.organization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    Optional<Organization> findOrganizationBySlug(String slug);

    Boolean existsOrganizationBySlug(String slug);

    // "My organizations": the caller's user id in the WHERE is the whole point (findAll() listed everyone's).
    // Rooted on Organization, so the Pageable's sort properties (name, createdAt, id) resolve against "o".
    @Query(value = """
            select new com.abhinav.taskflow.organization.OrganizationResponseDto(
                       o.id, o.name, o.slug, m.role, o.createdBy, o.createdAt)
              from Organization o
              join OrganizationMembership m on m.organizationId = o.id
             where m.userAccountId = :userAccountId
            """,
            countQuery = """
            select count(m)
              from OrganizationMembership m
             where m.userAccountId = :userAccountId
            """)
    Page<OrganizationResponseDto> findMine(@Param("userAccountId") Long userAccountId, Pageable pageable);
}
