package com.abhinav.taskflow.organization.invitation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

// Not tenant-filtered: every query names organizationId (organization side) or email (invitee side) itself. A query
// with neither is a cross-tenant read. "Open" is spelled exactly like uk_organization_invitations_open's predicate;
// "unexpired" is always a :now from the injected Clock, never SQL now(), so tests can move time.
// Bulk statements bypass JPA auditing: each change sets updatedAt and updatedBy itself.
@Repository
public interface OrganizationInvitationRepository extends JpaRepository<OrganizationInvitation, Long> {

    // Organization side: the admins' list. Rooted on "i", so the Pageable's createdAt resolves to i.createdAt
    @Query(value = """
            select new com.abhinav.taskflow.organization.invitation.InvitationResponse(
                       i.id, i.email, i.role, i.createdBy, i.createdAt, i.expiresAt)
              from OrganizationInvitation i
             where i.organizationId = :organizationId
               and i.acceptedAt is null and i.declinedAt is null and i.revokedAt is null
               and i.expiresAt > :now
            """,
            countQuery = """
            select count(i)
              from OrganizationInvitation i
             where i.organizationId = :organizationId
               and i.acceptedAt is null and i.declinedAt is null and i.revokedAt is null
               and i.expiresAt > :now
            """)
    Page<InvitationResponse> findOpen(@Param("organizationId") Long organizationId,
                                      @Param("now") Instant now,
                                      Pageable pageable);

    // Invitee side: "my invitations", across organizations; served by ix_organization_invitations_open_email
    @Query(value = """
            select new com.abhinav.taskflow.organization.invitation.MyInvitationResponse(
                       i.id, o.id, o.name, i.role, i.createdBy, i.expiresAt)
              from OrganizationInvitation i
              join Organization o on o.id = i.organizationId
             where i.email = :email
               and i.acceptedAt is null and i.declinedAt is null and i.revokedAt is null
               and i.expiresAt > :now
            """,
            countQuery = """
            select count(i)
              from OrganizationInvitation i
             where i.email = :email
               and i.acceptedAt is null and i.declinedAt is null and i.revokedAt is null
               and i.expiresAt > :now
            """)
    Page<MyInvitationResponse> findOpenFor(@Param("email") String email,
                                           @Param("now") Instant now,
                                           Pageable pageable);

    // Invite, step 1: closes the open invitation for (organization, email), EXPIRED OR NOT (expiry can't be in the
    // index predicate, so an expired one still holds the slot). 0 or 1 rows; both are fine
    @Modifying(flushAutomatically = true)
    @Query("""
            update OrganizationInvitation i
               set i.revokedAt = :now,
                   i.updatedAt = :now,
                   i.updatedBy = :updatedBy
             where i.organizationId = :organizationId
               and i.email = :email
               and i.acceptedAt is null and i.declinedAt is null and i.revokedAt is null
            """)
    int revokeOpenFor(@Param("organizationId") Long organizationId,
                      @Param("email") String email,
                      @Param("now") Instant now,
                      @Param("updatedBy") String updatedBy);

    // Organization side. 0 rows: unknown, another organization's, or no longer open
    @Modifying(flushAutomatically = true)
    @Query("""
            update OrganizationInvitation i
               set i.revokedAt = :now,
                   i.updatedAt = :now,
                   i.updatedBy = :updatedBy
             where i.id = :invitationId
               and i.organizationId = :organizationId
               and i.acceptedAt is null and i.declinedAt is null and i.revokedAt is null
            """)
    int revoke(@Param("organizationId") Long organizationId,
               @Param("invitationId") Long invitationId,
               @Param("now") Instant now,
               @Param("updatedBy") String updatedBy);

    // Invitee side. The caller's email in the WHERE is the IDOR guard: someone else's id matches 0 rows.
    // 0 rows: unknown, not yours, expired, answered or revoked. 1 row: this transaction holds the row lock until commit
    @Modifying(flushAutomatically = true)
    @Query("""
            update OrganizationInvitation i
               set i.acceptedAt = :now,
                   i.updatedAt = :now,
                   i.updatedBy = :updatedBy
             where i.id = :invitationId
               and i.email = :email
               and i.acceptedAt is null and i.declinedAt is null and i.revokedAt is null
               and i.expiresAt > :now
            """)
    int accept(@Param("invitationId") Long invitationId,
               @Param("email") String email,
               @Param("now") Instant now,
               @Param("updatedBy") String updatedBy);

    // Same WHERE as accept
    @Modifying(flushAutomatically = true)
    @Query("""
            update OrganizationInvitation i
               set i.declinedAt = :now,
                   i.updatedAt = :now,
                   i.updatedBy = :updatedBy
             where i.id = :invitationId
               and i.email = :email
               and i.acceptedAt is null and i.declinedAt is null and i.revokedAt is null
               and i.expiresAt > :now
            """)
    int decline(@Param("invitationId") Long invitationId,
                @Param("email") String email,
                @Param("now") Instant now,
                @Param("updatedBy") String updatedBy);
}
