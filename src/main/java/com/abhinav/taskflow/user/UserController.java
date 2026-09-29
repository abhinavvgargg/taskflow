package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.security.TaskflowPrincipal;
import com.abhinav.taskflow.common.web.PageQuery;
import com.abhinav.taskflow.common.web.PageResponse;
import com.abhinav.taskflow.common.web.PageableFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

    private final LoginHistoryService loginHistoryService;
    private final PageableFactory pageableFactory;

    @GetMapping("/login-history")
    public ResponseEntity<PageResponse<LoginHistoryResponse>> getLoginHistory(@AuthenticationPrincipal(errorOnInvalidType = true) TaskflowPrincipal taskflowPrincipal, PageQuery pageQuery) {
        Pageable pageable = pageableFactory.of(pageQuery.page(), pageQuery.size(), LoginHistoryService.SORT, Set.of("occurredAt", "id"));
        return ResponseEntity.ok(PageResponse.from(loginHistoryService.getLoginHistory(taskflowPrincipal.getId(), pageable)));
    }
}
