package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.tenant.OrganizationRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// Not tenant-filtered (an access table): every query here puts organization_id and/or user_account_id in its WHERE
// itself. A query without one of them is a cross-tenant read.
@Repository
public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, Long> {

    // On the path of every org-scoped request: selects the role, never an entity; served by uk_organization_memberships_org_user
    @Query("""
            select m.role
              from OrganizationMembership m
             where m.organizationId = :organizationId
               and m.userAccountId = :userAccountId
            """)
    Optional<OrganizationRole> findRole(@Param("organizationId") Long organizationId,
                                        @Param("userAccountId") Long userAccountId);

//    Optional<OrganizationRole> findRoleByOrganizationIdAndUserAccountId(Long organizationId, Long userAccountId);

    // The order comes from the Pageable. Sort properties are resolved against the root alias "m" unless they start with
    // a join variable: MembershipService maps the API's "username" to "u.username" and "joinedAt" to "createdAt".
    @Query(value = """
            select new com.abhinav.taskflow.organization.MemberResponseDto(
                       u.id, u.username, u.displayName, m.role, m.createdAt)
              from OrganizationMembership m
              join UserAccount u on u.id = m.userAccountId
             where m.organizationId = :organizationId
            """,
            countQuery = """
            select count(m)
              from OrganizationMembership m
             where m.organizationId = :organizationId
            """)
    Page<MemberResponseDto> findMembers(@Param("organizationId") Long organizationId, Pageable pageable);
}
