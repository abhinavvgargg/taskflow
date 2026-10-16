package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.web.PageQuery;
import com.abhinav.taskflow.common.web.PageResponse;
import com.abhinav.taskflow.common.web.PageableFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;

/**
 * The collection (create, "my organizations") is not org-scoped. Everything under /{orgId} passes the tenant gate
 * first, which reads the variable by the name {@code orgId}: org-scoped handlers don't bind it themselves, because the
 * service takes the organization from TenantContext (the value the gate checked), never from the path.
 */
@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    // "id" is allowed because the default sort names it (PageableFactory validates whatever sort it's given)
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("name", "createdAt", "id");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"));

    private final OrganizationService organizationService;
    private final PageableFactory pageableFactory;

    @PostMapping
    public ResponseEntity<OrganizationResponseDto> createOrganization(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                                                      @Valid @RequestBody OrganizationRequestDto organizationRequestDto)
    {
        OrganizationResponseDto organizationResponseDto = organizationService.create(organizationRequestDto, caller);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{orgId}")
                .buildAndExpand(organizationResponseDto.id())
                .toUri();

        return ResponseEntity
                .created(location)
                .body(organizationResponseDto);
    }

    @GetMapping("/{orgId}")
    public ResponseEntity<OrganizationResponseDto> getOrganization()
    {
        return ResponseEntity
                .ok(organizationService.get());
    }

    @GetMapping
    public ResponseEntity<PageResponse<OrganizationResponseDto>> getMyOrganizations(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                                                                    PageQuery query, Sort sort)
    {
        Sort effective = sort.isUnsorted() ? DEFAULT_SORT : sort;
        Pageable pageable = pageableFactory.of(query.page(), query.size(), effective, ALLOWED_SORT_FIELDS);
        return ResponseEntity
                .ok(PageResponse.from(organizationService.listMine(caller, pageable)));
    }
}
