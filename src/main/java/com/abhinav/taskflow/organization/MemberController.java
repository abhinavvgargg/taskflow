package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.web.PageQuery;
import com.abhinav.taskflow.common.web.PageResponse;
import com.abhinav.taskflow.common.web.PageableFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** An organization's members. Org-scoped: the gate has already checked {orgId} and filled TenantContext. */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/members")
@RequiredArgsConstructor
public class MemberController {

    private static final Set<String> SORTABLE = Set.of("username", "joinedAt");

    private final MembershipService membershipService;
    private final PageableFactory pageableFactory;

    @GetMapping
    public ResponseEntity<PageResponse<MemberResponseDto>> listMembers(PageQuery query, Sort sort)
    {
        Sort effective = sort.isUnsorted() ? MembershipService.LIST_SORT : sort;
        Pageable pageable = pageableFactory.of(query.page(), query.size(), effective, SORTABLE);
        return ResponseEntity.ok(PageResponse.from(membershipService.listMembers(pageable)));
    }
}
