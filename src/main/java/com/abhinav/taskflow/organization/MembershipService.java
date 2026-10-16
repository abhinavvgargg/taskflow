package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** The members of the current organization (§3.2 adds role changes, removal, leaving and transfer). */
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

    @Transactional(readOnly = true)
    public Page<MemberResponseDto> listMembers(Pageable pageable)
    {
        return membershipRepository.findMembers(tenantContext.organizationId(), toQueryPaths(pageable));
    }

    private static Pageable toQueryPaths(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .map(order -> order.withProperty(SORT_PATHS.getOrDefault(order.getProperty(), order.getProperty())))
                .toList());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
