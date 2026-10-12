package com.abhinav.taskflow.user;

import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.common.web.PageQuery;
import com.abhinav.taskflow.common.web.PageResponse;
import com.abhinav.taskflow.common.web.PageableFactory;
import com.abhinav.taskflow.user.session.SessionResponse;
import com.abhinav.taskflow.user.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * "My sessions": list them, end one, end all. Authenticated by the BEARER token (unlike logout, which uses the cookie),
 * so these live under /users/me and not under /auth, where §2.2's resolver ignores bearer tokens on purpose.
 *
 * <p>The owner is always the principal; no endpoint takes an account id.
 */
@RestController
@RequestMapping("/api/v1/users/me/sessions")
@RequiredArgsConstructor
public class SessionController {

    private static final Set<String> SORTABLE = Set.of("lastRefreshedAt", "id");

    private final SessionWorkflow sessionWorkflow;
    private final PageableFactory pageableFactory;
    private final RefreshCookie refreshCookie;

    @GetMapping
    public ResponseEntity<PageResponse<SessionResponse>> listSessions(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                                                      PageQuery pageQuery) {
        Pageable pageable = pageableFactory.of(pageQuery.page(), pageQuery.size(), SessionService.LIST_SORT, SORTABLE);
        return ResponseEntity.ok(PageResponse.from(sessionWorkflow.listSessions(caller, pageable)));
    }

    /**
     * Ends one session. 404 SESSION_NOT_FOUND when nothing was revoked, for any reason. Ending the CURRENT session also
     * clears the refresh cookie: the Set-Cookie names the cookie's own Path (/api/v1/auth), which a response from
     * /users/me is allowed to do, and which the browser needs in order to match and delete it.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revokeSession(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                              @PathVariable Long sessionId, HttpServletRequest request) {
        sessionWorkflow.revokeSession(caller, sessionId, ClientInfo.from(request));

        ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
        if (sessionId.equals(caller.sessionId())) {
            response.header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString());
        }
        return response.build();
    }

    /** "Log out everywhere", this device included, so the cookie is always cleared. */
    @DeleteMapping
    public ResponseEntity<Void> revokeAllSessions(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller,
                                                  HttpServletRequest request) {
        sessionWorkflow.logOutEverywhere(caller, ClientInfo.from(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }
}
