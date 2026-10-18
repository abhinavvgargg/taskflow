package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.common.web.PageQuery;
import com.abhinav.taskflow.common.web.PageResponse;
import com.abhinav.taskflow.common.web.PageableFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * An organization's members, and the changes to them. Org-scoped: the gate has already checked {orgId} and filled
 * TenantContext, so no method binds {orgId} itself (the services read the organization from the context).
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
@RequiredArgsConstructor
public class MemberController {

    private static final Set<String> SORTABLE = Set.of("username", "joinedAt");

    private final MembershipService membershipService;
    private final MembershipWorkflow membershipWorkflow;
    private final PageableFactory pageableFactory;

    @GetMapping("/members")
    public ResponseEntity<PageResponse<MemberResponseDto>> listMembers(PageQuery query, Sort sort)
    {
        Sort effective = sort.isUnsorted() ? MembershipService.LIST_SORT : sort;
        Pageable pageable = pageableFactory.of(query.page(), query.size(), effective, SORTABLE);
        return ResponseEntity.ok(PageResponse.from(membershipService.listMembers(pageable)));
    }

    // "role" is validated as a String (ADMIN|MEMBER), so OWNER, GOD and admin all get the same 400 with field: role;
    // valueOf can't fail after @Pattern
    @PatchMapping("/members/{userId}")
    public ResponseEntity<MemberResponseDto> changeRole(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                                        @PathVariable Long userId,
                                                        @Valid @RequestBody ChangeRoleRequest request)
    {
        return ResponseEntity.ok(membershipService.changeRole(userId, OrganizationRole.valueOf(request.role()), caller));
    }

    // The literal "/members/me" wins over "/members/{userId}" (a more specific pattern), so "me" never reaches the
    // Long variable: leaving and removing stay two endpoints
    @DeleteMapping("/members/me")
    public ResponseEntity<Void> leave(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller)
    {
        membershipService.leave(caller);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long userId)
    {
        membershipService.remove(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ownership-transfer")
    public ResponseEntity<Void> transferOwnership(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                                  @Valid @RequestBody TransferOwnershipRequest request,
                                                  HttpServletRequest httpRequest)
    {
        membershipWorkflow.transferOwnership(caller, request, ClientInfo.from(httpRequest));
        return ResponseEntity.noContent().build();
    }
}
