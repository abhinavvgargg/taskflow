package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.security.SessionStatus;
import com.abhinav.taskflow.common.security.TaskflowAuthenticationToken;
import com.abhinav.taskflow.common.security.TaskflowSecurityConfig;
import com.abhinav.taskflow.common.security.UserRole;
import com.abhinav.taskflow.common.tenant.MembershipLookup;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.tenant.TenantContext;
import com.abhinav.taskflow.common.web.PageableFactory;
import com.abhinav.taskflow.user.TaskflowUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrganizationController.class)
@Import({TaskflowSecurityConfig.class, TenantContext.class})
public class OrganizationControllerTest {

    private static final AuthenticatedUser ALICE = new AuthenticatedUser(1L, "alice", UserRole.USER, 10L);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizationService;

    @MockitoBean
    private PageableFactory pageableFactory;

    @MockitoBean
    private TaskflowUserDetailsService taskflowUserDetailsService;

    // TaskflowSecurityConfig needs both to start; JwtKeyConfig and user.session aren't part of a web slice
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private SessionStatus sessionStatus;

    // The tenant gate is in every web slice (it's a HandlerInterceptor); its lookup lives in organization's JPA layer
    @MockitoBean
    private MembershipLookup membershipLookup;

    // The gate and @AuthenticationPrincipal(errorOnInvalidType = true) both need OUR principal type:
    // @WithMockUser's Spring User would be a 500 here, by design
    private static RequestPostProcessor asAlice() {
        return authentication(new TaskflowAuthenticationToken(ALICE));
    }

    @Test
    void getById_asMember_returnsOrganizationJsonWithRole() throws Exception {
        given(membershipLookup.roleOf(7L, ALICE.id())).willReturn(Optional.of(OrganizationRole.MEMBER));
        given(organizationService.get()).willReturn(new OrganizationResponseDto(7L, "Acme Corp", "acme-corp",
                OrganizationRole.MEMBER, "system", Instant.parse("2026-09-01T10:00:00Z")));

        mockMvc.perform(get("/api/v1/organizations/{orgId}", 7L).with(asAlice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.slug").value("acme-corp"))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void getById_asNonMember_is404_andTheServiceIsNeverCalled() throws Exception {
        given(membershipLookup.roleOf(7L, ALICE.id())).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/organizations/{orgId}", 7L).with(asAlice()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"));

        verifyNoInteractions(organizationService);
    }

    @Test
    void create_withInvalidBody_returns400ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/organizations")
                        .with(asAlice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "slug": "BAD SLUG"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("name", "slug")))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        verifyNoInteractions(organizationService);   // validation stopped it before the service
    }

    // Regression test for the @Max-on-a-String bug: a perfectly normal name must be accepted.
    // The collection isn't org-scoped: the gate doesn't run, so nothing is stubbed on MembershipLookup.
    @Test
    void create_withValidBody_returns201WithLocationAndOwnerRole() throws Exception {
        var request = new OrganizationRequestDto("Acme Corp", "acme-corp");
        given(organizationService.create(eq(request), any(AuthenticatedUser.class))).willReturn(
                new OrganizationResponseDto(51L, "Acme Corp", "acme-corp", OrganizationRole.OWNER, "alice",
                        Instant.parse("2026-09-01T10:00:00Z")));

        mockMvc.perform(post("/api/v1/organizations")
                        .with(asAlice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Acme Corp", "slug": "acme-corp"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/organizations/51")))
                .andExpect(jsonPath("$.id").value(51))
                .andExpect(jsonPath("$.role").value("OWNER"));

        verifyNoInteractions(membershipLookup);
    }
}
