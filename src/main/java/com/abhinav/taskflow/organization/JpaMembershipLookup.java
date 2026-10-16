package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.tenant.MembershipLookup;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** One indexed query per org-scoped request, selecting the role (no entity), in its own read-only transaction. No catch. */
@Component
@RequiredArgsConstructor
class JpaMembershipLookup implements MembershipLookup {

    private final OrganizationMembershipRepository membershipRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<OrganizationRole> roleOf(long organizationId, long userAccountId) {
        return membershipRepository.findRole(organizationId, userAccountId);
    }
}
