package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.error.ApplicationException;
import com.abhinav.taskflow.common.error.RequestForbiddenException;
import com.abhinav.taskflow.common.error.ResourceConflictException;
import com.abhinav.taskflow.common.error.ResourceNotFoundException;
import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.tenant.TenantContext;
import com.abhinav.taskflow.common.tenant.TenantErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * The members of the current organization: list them, change a role, remove one, leave, transfer ownership.
 *
 * <p>Every change has the same shape: READ the target's role, DECIDE with D6's rule ({@link OrganizationRole}), then
 * WRITE with one conditional statement whose WHERE repeats the role that was read. If anything changed in between,
 * the statement matches 0 rows and the answer is 409, never a lost update. The organization always comes from
 * {@link TenantContext} (the one the gate checked), never from a parameter.
 */
@Service
@RequiredArgsConstructor
public class MembershipService {

    public static final Sort LIST_SORT = Sort.by(Sort.Direction.ASC, "username");

    // API sort names → paths in OrganizationMembershipRepository.findMembers. "u." is the joined account and is left
    // alone by Spring Data; anything else is prefixed with the root alias "m." (so "createdAt" becomes m.createdAt).
    private static final Map<String, String> SORT_PATHS = Map.of(
            "username", "u.username",
            "joinedAt", "createdAt");

    private final OrganizationMembershipRepository membershipRepository;
    private final TenantContext tenantContext;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<MemberResponseDto> listMembers(Pageable pageable)
    {
        return membershipRepository.findMembers(tenantContext.organizationId(), toQueryPaths(pageable));
    }

    /**
     * The target's role becomes {@code newRole}, if both are strictly below the caller's (so, in practice, only the
     * owner changes roles). The member's current role is a no-op: 200, nothing written, so updated_by never claims a
     * change nobody made. The rule runs first, so a no-op is only possible for someone allowed to make it.
     */
    @Transactional
    public MemberResponseDto changeRole(Long targetUserId, OrganizationRole newRole, AuthenticatedUser caller)
    {
        long organizationId = tenantContext.organizationId();
        OrganizationRole currentRole = roleOf(targetUserId);

        if (!tenantContext.role().mayChangeRole(currentRole, newRole)) {
            throw cannotManage(targetUserId, "You can only change the role of members below you, to a role below yours");
        }
        if (currentRole != newRole
                && membershipRepository.changeRole(organizationId, targetUserId, currentRole, newRole, clock.instant(), caller.username()) == 0) {
            throw membershipChanged(targetUserId);
        }
        // Read back AFTER the update: the response is the stored row
        return membershipRepository.findMember(organizationId, targetUserId)
                .orElseThrow(() -> memberNotFound(targetUserId));
    }

    /** Removes a member strictly below the caller: owners remove admins and members, admins remove members. */
    @Transactional
    public void remove(Long targetUserId)
    {
        OrganizationRole currentRole = roleOf(targetUserId);

        // Yourself included: your role isn't below your own. Leaving is DELETE /members/me
        if (!tenantContext.role().mayRemove(currentRole)) {
            throw cannotManage(targetUserId, "You can only remove members below you; to leave, use /members/me");
        }
        if (membershipRepository.remove(tenantContext.organizationId(), targetUserId, currentRole) == 0) {
            throw membershipChanged(targetUserId);
        }
    }

    /** The caller leaves. The owner can't (an organization always has one): transfer ownership first. */
    @Transactional
    public void leave(AuthenticatedUser caller)
    {
        long organizationId = tenantContext.organizationId();
        if (membershipRepository.leave(organizationId, caller.id()) == 1) {
            return;
        }
        // 0 rows: still a member means the owner (the statement excludes only the owner); gone means removed meanwhile
        if (membershipRepository.findRole(organizationId, caller.id()).isPresent()) {
            throw new ResourceConflictException(OrganizationErrorCode.OWNER_CANNOT_LEAVE,
                    "The owner can't leave the organization; transfer ownership first");
        }
        throw new ResourceNotFoundException(TenantErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found");
    }

    /**
     * Step 1 demotes the caller to ADMIN, step 2 promotes the target to OWNER, in that order and in one transaction:
     * the one-owner index is checked at each statement (promote-first would collide with the caller), the "has an
     * owner" trigger only at commit (the moment with no owner between the two is allowed).
     *
     * <p>Called by {@link MembershipWorkflow} after the owner check, the self-transfer check and the password, which
     * run outside this transaction. Step 1's WHERE re-checks "still the owner" in the database: of two concurrent
     * transfers, the second matches 0 rows.
     */
    @Transactional
    public void transferOwnership(Long newOwnerId, AuthenticatedUser caller)
    {
        long organizationId = tenantContext.organizationId();
        Instant now = clock.instant();

        if (membershipRepository.demoteOwner(organizationId, caller.id(), now, caller.username()) == 0) {
            throw new ResourceConflictException(OrganizationErrorCode.MEMBERSHIP_CHANGED,
                    "You are no longer the owner of this organization");
        }
        // 0 rows: not a member of THIS organization. Throwing rolls step 1 back: the caller stays the owner
        if (membershipRepository.promoteToOwner(organizationId, newOwnerId, now, caller.username()) == 0) {
            throw memberNotFound(newOwnerId);
        }
    }

    // A target who isn't a member of THIS organization, including a member of another one: the same 404. The caller is
    // inside the organization, so "not a member" reveals nothing they couldn't see in the members list.
    private OrganizationRole roleOf(Long targetUserId) {
        return membershipRepository.findRole(tenantContext.organizationId(), targetUserId)
                .orElseThrow(() -> memberNotFound(targetUserId));
    }

    private static ApplicationException memberNotFound(Long userId) {
        return new ResourceNotFoundException(OrganizationErrorCode.MEMBER_NOT_FOUND, "Member not found")
                .with("userId", userId);
    }

    private static ApplicationException cannotManage(Long userId, String detail) {
        return new RequestForbiddenException(OrganizationErrorCode.CANNOT_MANAGE_MEMBER, detail)
                .with("userId", userId);
    }

    private static ApplicationException membershipChanged(Long userId) {
        return new ResourceConflictException(OrganizationErrorCode.MEMBERSHIP_CHANGED,
                "The member's role changed, or they left, since it was read; reload and try again")
                .with("userId", userId);
    }

    private static Pageable toQueryPaths(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .map(order -> order.withProperty(SORT_PATHS.getOrDefault(order.getProperty(), order.getProperty())))
                .toList());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
