package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.web.PageQuery;
import com.abhinav.taskflow.common.web.PageResponse;
import com.abhinav.taskflow.common.web.PageableFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "createdAt", "name", "slug", "updatedAt");

    private final OrganizationService organizationService;
    private final PageableFactory pageableFactory;

    @PostMapping
    public ResponseEntity<OrganizationResponseDto> createOrganization(@Valid @RequestBody OrganizationRequestDto organizationRequestDto)
    {
        OrganizationResponseDto organizationResponseDto = organizationService.createOrganization(organizationRequestDto);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(organizationResponseDto.id())
                .toUri();

        return ResponseEntity
                .created(location)
                .body(organizationResponseDto);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationResponseDto> getOrganizationById(@PathVariable Long id)
    {
        return ResponseEntity
                .ok(organizationService.findOrganizationById(id));
    }

    @GetMapping
    public ResponseEntity<PageResponse<OrganizationResponseDto>> getOrganizations (PageQuery query, Sort sort)
    {
        Pageable pageable = pageableFactory.of(query.page(), query.size(), sort, ALLOWED_SORT_FIELDS);
        return ResponseEntity
                .ok(PageResponse.from(organizationService.findAllOrganizations(pageable)));
    }
}
