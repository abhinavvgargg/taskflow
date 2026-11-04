package com.abhinav.taskflow.organization.invitation;

import com.abhinav.taskflow.common.error.ApplicationException;
import com.abhinav.taskflow.common.error.RequestForbiddenException;
import com.abhinav.taskflow.common.error.ResourceConflictException;
import com.abhinav.taskflow.common.error.ResourceNotFoundException;
import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.tenant.TenantContext;
import com.abhinav.taskflow.common.util.Normalize;
import com.abhinav.taskflow.organization.OrganizationMembership;
import com.abhinav.taskflow.organization.OrganizationMembershipRepository;
import com.abhinav.taskflow.user.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Invitations, from both sides.
 *
 * <p>Organization side (invite, listOpen, revoke): reached through the gate; the organization comes from
 * {@link TenantContext}, never from a parameter. Invitee side (listMine, accept, decline): NOT gated (no tenant exists
 * until you accept), so those methods never touch TenantContext; their key is the caller's current email, loaded by id.
 *
 * <p>Every change is one conditional UPDATE whose WHERE carries the owner's key and "open": 0 rows is the same 404,
 * whatever the reason.
 */
@Service
@RequiredArgsConstructor
public class InvitationService {

    public static final Sort LIST_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final OrganizationInvitationRepository invitationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final UserAccountRepository userAccountRepository;
    private final TenantContext tenantContext;
    private final InvitationProperties invitationProperties;
    private final Clock clock;

    // ---------------------------------------------------------------- organization side

    /**
     * Revoke-then-insert in one transaction: a re-invite replaces the open invitation (new role, new expiry). The INSERT
     * runs at commit; uk_organization_invitations_open decides a concurrent re-invite (409 RESOURCE_CONFLICT).
     */
    @Transactional
    public InvitationResponse invite(String rawEmail, OrganizationRole role, AuthenticatedUser caller)
    {
        // 1. Admin or above first, so a MEMBER gets ORGANIZATION_ROLE_REQUIRED, not ROLE_NOT_GRANTABLE
        tenantContext.requireAtLeast(OrganizationRole.ADMIN);

        // 2. Strictly below the caller (D6): an admin can't create a peer by invitation
        if (!tenantContext.role().mayInvite(role)) {
            throw new RequestForbiddenException(InvitationErrorCode.ROLE_NOT_GRANTABLE,
                    "You can only invite with a role below yours").with("role", role.name());
        }

        long organizationId = tenantContext.organizationId();
        String email = Normalize.normalizeEmail(rawEmail);

        // 3. A friendly 409; at accept time uk_organization_memberships_org_user is the real guard
        if (membershipRepository.existsMemberWithEmail(organizationId, email)) {
            throw new ResourceConflictException(InvitationErrorCode.ALREADY_MEMBER,
                    "This address already belongs to a member of the organization");
        }

        // 4. One clock reading for the revoke and the new expiry
        Instant now = clock.instant();
        invitationRepository.revokeOpenFor(organizationId, email, now, caller.username());
        OrganizationInvitation invitation = invitationRepository.save(
                OrganizationInvitation.create(organizationId, email, role, now.plus(invitationProperties.invitationTtl())));
        return InvitationResponse.from(invitation);
    }

    @Transactional(readOnly = true)
    public Page<InvitationResponse> listOpen(Pageable pageable)
    {
        tenantContext.requireAtLeast(OrganizationRole.ADMIN);
        return invitationRepository.findOpen(tenantContext.organizationId(), clock.instant(), pageable);
    }

    /** Any open invitation of THIS organization, expired or not (an expired one can't be accepted anyway). */
    @Transactional
    public void revoke(Long invitationId, AuthenticatedUser caller)
    {
        tenantContext.requireAtLeast(OrganizationRole.ADMIN);
        if (invitationRepository.revoke(tenantContext.organizationId(), invitationId, clock.instant(), caller.username()) == 0) {
            throw invitationNotFound();
        }
    }

    // ---------------------------------------------------------------- invitee side: no TenantContext below this line

    @Transactional(readOnly = true)
    public Page<MyInvitationResponse> listMine(AuthenticatedUser caller, Pageable pageable)
    {
        return invitationRepository.findOpenFor(emailOf(caller), clock.instant(), pageable);
    }

    /**
     * Claim, then join, in one transaction: an accepted invitation without a membership (or the reverse) is a state
     * nobody can repair from the API.
     */
    @Transactional
    public AcceptedInvitationResponse accept(AuthenticatedUser caller, Long invitationId)
    {
        // 1. Claim it. Decides between concurrent accepts (and an accept racing a revoke): the loser waits for this
        //    row lock, re-checks the WHERE after our commit, and matches 0 rows
        if (invitationRepository.accept(invitationId, emailOf(caller), clock.instant(), caller.username()) == 0) {
            throw invitationNotFound();
        }

        // 2. What it grants. organizationId and role are never updated, and we hold the row's lock: can't be stale
        OrganizationInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new IllegalStateException("Invitation " + invitationId + " vanished after it was accepted"));

        // 3. A friendly 409. Throwing rolls step 1 back: the invitation stays open, and the caller can decline it
        if (membershipRepository.findRole(invitation.getOrganizationId(), caller.id()).isPresent()) {
            throw new ResourceConflictException(InvitationErrorCode.ALREADY_MEMBER,
                    "You are already a member of this organization");
        }

        // 4. Join. uk_organization_memberships_org_user stays the guard (checked when the INSERT flushes, at commit)
        membershipRepository.save(OrganizationMembership.of(invitation.getOrganizationId(), caller.id(), invitation.getRole()));
        return new AcceptedInvitationResponse(invitation.getOrganizationId(), invitation.getRole());
    }

    @Transactional
    public void decline(AuthenticatedUser caller, Long invitationId)
    {
        if (invitationRepository.decline(invitationId, emailOf(caller), clock.instant(), caller.username()) == 0) {
            throw invitationNotFound();
        }
    }

    // The bearer principal carries no email (no PII in tokens): the CURRENT address, by id, on every call
    private String emailOf(AuthenticatedUser caller) {
        return userAccountRepository.findEmailById(caller.id())
                .orElseThrow(() -> new IllegalStateException("Authenticated account no longer exists"));
    }

    // No id and no reason in the body: unknown, not yours, expired, answered and revoked must all look the same
    private static ApplicationException invitationNotFound() {
        return new ResourceNotFoundException(InvitationErrorCode.INVITATION_NOT_FOUND, "Invitation not found");
    }
}
