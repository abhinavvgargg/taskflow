package com.abhinav.taskflow.common.tenant;

import java.util.Optional;

/**
 * "What is user U's role in organization O?", asked by the gate on every org-scoped request.
 * An interface here, implemented in organization, so common never imports a feature (like SessionStatus).
 */
public interface MembershipLookup {

    /**
     * The caller's role, or empty when they aren't a member OR the organization doesn't exist: the gate must not be
     * able to tell those apart. Database errors propagate: an outage is a 500, never a 404.
     */
    Optional<OrganizationRole> roleOf(long organizationId, long userAccountId);
}
