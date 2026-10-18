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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Request validation and routing of the member endpoints; the services are mocked. */
@WebMvcTest(MemberController.class)
@Import({TaskflowSecurityConfig.class, TenantContext.class})
class MemberControllerTest {

    private static final AuthenticatedUser ALICE = new AuthenticatedUser(1L, "alice", UserRole.USER, 10L);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MembershipService membershipService;

    @MockitoBean
    MembershipWorkflow membershipWorkflow;

    @MockitoBean
    PageableFactory pageableFactory;

    @MockitoBean
    MembershipLookup membershipLookup;

    // TaskflowSecurityConfig needs these to start
    @MockitoBean
    TaskflowUserDetailsService taskflowUserDetailsService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    SessionStatus sessionStatus;

    @BeforeEach
    void aliceOwnsOrganization42() {
        given(membershipLookup.roleOf(42L, ALICE.id())).willReturn(Optional.of(OrganizationRole.OWNER));
    }

    private static RequestPostProcessor asAlice() {
        return authentication(new TaskflowAuthenticationToken(ALICE));
    }

    // One mistake, one answer: OWNER (a real role, but never granted), an unknown role, the wrong case and an empty
    // string all get the same 400 on the "role" field. An enum-typed field would answer GOD with a parse error instead
    @ParameterizedTest(name = "role \"{0}\" -> 400 on field role")
    @ValueSource(strings = {"OWNER", "GOD", "admin", ""})
    void changeRole_withARoleThatCantBeGranted_is400OnTheRoleField(String role) throws Exception {
        mockMvc.perform(patch("/api/v1/organizations/42/members/3").with(asAlice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"" + role + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", contains("role")));

        verifyNoInteractions(membershipService);
    }

    @Test
    void changeRole_withoutARole_is400OnTheRoleField() throws Exception {
        mockMvc.perform(patch("/api/v1/organizations/42/members/3").with(asAlice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", contains("role")));

        verifyNoInteractions(membershipService);
    }

    @Test
    void changeRole_withAValidRole_passesTheEnumToTheService() throws Exception {
        mockMvc.perform(patch("/api/v1/organizations/42/members/3").with(asAlice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"ADMIN\"}"))
                .andExpect(status().isOk());

        verify(membershipService).changeRole(any(), any(), any());
    }

    // The literal /members/me must win over /members/{userId}: if it didn't, "me" would hit a Long variable (400)
    @Test
    void deleteMembersMe_isLeave_notARemoval() throws Exception {
        mockMvc.perform(delete("/api/v1/organizations/42/members/me").with(asAlice()))
                .andExpect(status().isNoContent());

        verify(membershipService).leave(any());
        verify(membershipService, never()).remove(anyLong());
    }

    @Test
    void transfer_withoutAUserIdOrAPassword_is400() throws Exception {
        mockMvc.perform(post("/api/v1/organizations/42/ownership-transfer").with(asAlice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(membershipWorkflow);
    }
}
