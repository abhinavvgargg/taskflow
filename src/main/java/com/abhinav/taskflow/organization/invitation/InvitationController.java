package com.abhinav.taskflow.organization.invitation;

import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.web.PageQuery;
import com.abhinav.taskflow.common.web.PageResponse;
import com.abhinav.taskflow.common.web.PageableFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;

/**
 * The organization's invitations. Org-scoped: the gate has checked {orgId} and filled TenantContext, so no method binds
 * {orgId} itself.
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private static final Set<String> SORTABLE = Set.of("createdAt");

    private final InvitationService invitationService;
    private final PageableFactory pageableFactory;

    // "role" is validated as a String (ADMIN|MEMBER), so valueOf can't fail after @Pattern
    @PostMapping
    public ResponseEntity<InvitationResponse> invite(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                                     @Valid @RequestBody InviteRequest request)
    {
        InvitationResponse invitation = invitationService.invite(request.email(), OrganizationRole.valueOf(request.role()), caller);

        // Names the resource DELETE acts on; there's no GET for one invitation (RFC 9110 doesn't require one)
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{invitationId}")
                .buildAndExpand(invitation.id())
                .toUri();

        return ResponseEntity.created(location).body(invitation);
    }

    // Newest first, always (the client's sort isn't offered, like /users/me/sessions)
    @GetMapping
    public ResponseEntity<PageResponse<InvitationResponse>> listOpen(PageQuery query)
    {
        Pageable pageable = pageableFactory.of(query.page(), query.size(), InvitationService.LIST_SORT, SORTABLE);
        return ResponseEntity.ok(PageResponse.from(invitationService.listOpen(pageable)));
    }

    @DeleteMapping("/{invitationId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                       @PathVariable Long invitationId)
    {
        invitationService.revoke(invitationId, caller);
        return ResponseEntity.noContent().build();
    }
}
