package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.tenant.OrganizationRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

// Not tenant-filtered (an access table): every query here puts organization_id and/or user_account_id in its WHERE
// itself. A query without one of them is a cross-tenant read.
//
// Changes are conditional statements, never entity edits (Phase 2 D11): each WHERE repeats what the service decided on
// (the role it read), so a concurrent change makes it match 0 rows instead of being overwritten. Bulk statements bypass
// JPA auditing, so the ones that change a row set updated_at and updated_by themselves.
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

    // Invite: is this address already a member here? (The accepted "email oracle" for admins, see §3.3's traps.)
    // user_accounts' unique email index finds the account, uk_organization_memberships_org_user the membership
    @Query("""
            select case when count(m) > 0 then true else false end
              from OrganizationMembership m
              join UserAccount u on u.id = m.userAccountId
             where m.organizationId = :organizationId
               and u.email = :email
            """)
    boolean existsMemberWithEmail(@Param("organizationId") Long organizationId,
                                  @Param("email") String email);

    // One member, as the list shows them: read AFTER a change, so the response is the stored row (a projection, so no
    // entity the bulk UPDATE left stale in the persistence context)
    @Query("""
            select new com.abhinav.taskflow.organization.MemberResponseDto(
                       u.id, u.username, u.displayName, m.role, m.createdAt)
              from OrganizationMembership m
              join UserAccount u on u.id = m.userAccountId
             where m.organizationId = :organizationId
               and m.userAccountId = :userAccountId
            """)
    Optional<MemberResponseDto> findMember(@Param("organizationId") Long organizationId,
                                           @Param("userAccountId") Long userAccountId);

    // 0 rows: the member left or was removed, or their role changed since the service read it
    @Modifying(flushAutomatically = true)
    @Query("""
            update OrganizationMembership m
               set m.role = :newRole,
                   m.updatedAt = :now,
                   m.updatedBy = :updatedBy
             where m.organizationId = :organizationId
               and m.userAccountId = :userAccountId
               and m.role = :expectedCurrent
            """)
    int changeRole(@Param("organizationId") Long organizationId,
                   @Param("userAccountId") Long userAccountId,
                   @Param("expectedCurrent") OrganizationRole expectedCurrent,
                   @Param("newRole") OrganizationRole newRole,
                   @Param("now") Instant now,
                   @Param("updatedBy") String updatedBy);

    // 0 rows: as for changeRole
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from OrganizationMembership m
             where m.organizationId = :organizationId
               and m.userAccountId = :userAccountId
               and m.role = :expectedCurrent
            """)
    int remove(@Param("organizationId") Long organizationId,
               @Param("userAccountId") Long userAccountId,
               @Param("expectedCurrent") OrganizationRole expectedCurrent);

    // Leaving: anyone but the owner. 0 rows: the caller is the owner, or is no longer a member
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from OrganizationMembership m
             where m.organizationId = :organizationId
               and m.userAccountId = :userAccountId
               and m.role <> com.abhinav.taskflow.common.tenant.OrganizationRole.OWNER
            """)
    int leave(@Param("organizationId") Long organizationId,
              @Param("userAccountId") Long userAccountId);

    // Transfer, step 1 (runs FIRST: the one-owner index is checked per statement). 0 rows: the caller isn't the owner
    // any more, e.g. a concurrent transfer won
    @Modifying(flushAutomatically = true)
    @Query("""
            update OrganizationMembership m
               set m.role = com.abhinav.taskflow.common.tenant.OrganizationRole.ADMIN,
                   m.updatedAt = :now,
                   m.updatedBy = :updatedBy
             where m.organizationId = :organizationId
               and m.userAccountId = :ownerId
               and m.role = com.abhinav.taskflow.common.tenant.OrganizationRole.OWNER
            """)
    int demoteOwner(@Param("organizationId") Long organizationId,
                    @Param("ownerId") Long ownerId,
                    @Param("now") Instant now,
                    @Param("updatedBy") String updatedBy);

    // Transfer, step 2. 0 rows: the target isn't a member of THIS organization (the caller's transaction then rolls back)
    @Modifying(flushAutomatically = true)
    @Query("""
            update OrganizationMembership m
               set m.role = com.abhinav.taskflow.common.tenant.OrganizationRole.OWNER,
                   m.updatedAt = :now,
                   m.updatedBy = :updatedBy
             where m.organizationId = :organizationId
               and m.userAccountId = :newOwnerId
               and m.role <> com.abhinav.taskflow.common.tenant.OrganizationRole.OWNER
            """)
    int promoteToOwner(@Param("organizationId") Long organizationId,
                       @Param("newOwnerId") Long newOwnerId,
                       @Param("now") Instant now,
                       @Param("updatedBy") String updatedBy);
}
