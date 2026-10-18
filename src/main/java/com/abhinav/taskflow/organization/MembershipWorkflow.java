package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.error.ResourceInvalidException;
import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.tenant.TenantContext;
import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.Reauthentication;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Ownership transfer, in the order the checks must run. Deliberately NOT transactional (the PasswordWorkflow shape):
 * the password check is bcrypt, ~100 ms on purpose, and must not hold a pooled database connection while it runs.
 * Only the last step, {@link MembershipService#transferOwnership}, opens a transaction.
 */
@Service
@RequiredArgsConstructor
public class MembershipWorkflow {

    private final TenantContext tenantContext;
    private final Reauthentication reauthentication;
    private final MembershipService membershipService;

    public void transferOwnership(AuthenticatedUser caller, TransferOwnershipRequest request, ClientInfo clientInfo) {
        // 1. Owner only. First, because it's free, and so a member's wrong password never counts toward their lockout
        tenantContext.requireAtLeast(OrganizationRole.OWNER);

        // 2. Not yourself: step 1 would demote you and step 2 promote you straight back, a 204 that did nothing
        if (request.userId().equals(caller.id())) {
            throw new ResourceInvalidException(OrganizationErrorCode.CANNOT_TRANSFER_TO_SELF,
                    "You already own this organization; choose another member").with("field", "userId");
        }

        // 3. Sudo mode: a stolen access token can't give the organization away
        reauthentication.confirmPassword(caller, request.currentPassword(), clientInfo);

        // 4. The transaction: demote, then promote
        membershipService.transferOwnership(request.userId(), caller);
    }
}
