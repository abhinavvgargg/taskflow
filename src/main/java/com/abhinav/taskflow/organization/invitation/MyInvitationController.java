package com.abhinav.taskflow.organization.invitation;

import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.web.PageQuery;
import com.abhinav.taskflow.common.web.PageResponse;
import com.abhinav.taskflow.common.web.PageableFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * Invitations addressed to the caller. Under /users/me, NOT /organizations: no tenant exists until you accept, so the
 * gate must not run here. The key is always the principal's own email; no endpoint takes an address or an account id.
 */
@RestController
@RequestMapping("/api/v1/users/me/invitations")
@RequiredArgsConstructor
public class MyInvitationController {

    private static final Set<String> SORTABLE = Set.of("createdAt");

    private final InvitationService invitationService;
    private final PageableFactory pageableFactory;

    @GetMapping
    public ResponseEntity<PageResponse<MyInvitationResponse>> listMine(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                                                       PageQuery query)
    {
        Pageable pageable = pageableFactory.of(query.page(), query.size(), InvitationService.LIST_SORT, SORTABLE);
        return ResponseEntity.ok(PageResponse.from(invitationService.listMine(caller, pageable)));
    }

    @PostMapping("/{invitationId}/accept")
    public ResponseEntity<AcceptedInvitationResponse> accept(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                                             @PathVariable Long invitationId)
    {
        return ResponseEntity.ok(invitationService.accept(caller, invitationId));
    }

    @PostMapping("/{invitationId}/decline")
    public ResponseEntity<Void> decline(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                        @PathVariable Long invitationId)
    {
        invitationService.decline(caller, invitationId);
        return ResponseEntity.noContent().build();
    }
}
