package com.abhinav.taskflow.common.tenant;

import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.security.SessionStatus;
import com.abhinav.taskflow.common.security.TaskflowAuthenticationToken;
import com.abhinav.taskflow.common.security.TaskflowSecurityConfig;
import com.abhinav.taskflow.common.security.UserRole;
import com.abhinav.taskflow.user.TaskflowUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The gate on its own, in front of a probe controller that exists only in this test: one endpoint named the right
 * way ({orgId}) and one named wrong ({id}), which must FAIL rather than slip past the gate.
 *
 * <p>The probe is registered with @Import: inner classes of test classes are excluded from component scanning.
 */
@WebMvcTest(TenantGateInterceptorTest.ProbeController.class)
@Import({TaskflowSecurityConfig.class, TenantContext.class, TenantGateInterceptorTest.ProbeController.class})
class TenantGateInterceptorTest {

    private static final AuthenticatedUser BOB = new AuthenticatedUser(2L, "bob", UserRole.USER, 20L);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MembershipLookup membershipLookup;

    // TaskflowSecurityConfig needs these to start
    @MockitoBean
    TaskflowUserDetailsService taskflowUserDetailsService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    SessionStatus sessionStatus;

    @RestController
    static class ProbeController {

        private final TenantContext tenantContext;

        ProbeController(TenantContext tenantContext) {
            this.tenantContext = tenantContext;
        }

        @GetMapping("/api/v1/organizations/{orgId}/probe")
        String probe() {
            return tenantContext.organizationId() + ":" + tenantContext.role();
        }

        // Deliberately mis-named: the gate can't find {orgId}
        @GetMapping("/api/v1/organizations/{id}/unnamed")
        String unnamed() {
            return "reached";
        }
    }

    private static RequestPostProcessor asBob() {
        return authentication(new TaskflowAuthenticationToken(BOB));
    }

    @Test
    void aMember_passes_andTheContextHoldsTheOrganizationAndRole() throws Exception {
        given(membershipLookup.roleOf(42L, BOB.id())).willReturn(Optional.of(OrganizationRole.ADMIN));

        mockMvc.perform(get("/api/v1/organizations/42/probe").with(asBob()))
                .andExpect(status().isOk())
                .andExpect(content().string("42:ADMIN"));
    }

    @Test
    void aNonMember_gets404OrganizationNotFound() throws Exception {
        given(membershipLookup.roleOf(42L, BOB.id())).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/organizations/42/probe").with(asBob()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Organization not found"))
                .andExpect(jsonPath("$.id").doesNotExist());   // nothing request-specific beyond "instance"
    }

    @Test
    void aNonNumericOrgId_isTheSame404_notA400() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/acme/probe").with(asBob()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"));

        verify(membershipLookup, never()).roleOf(anyLong(), anyLong());
    }

    @Test
    void aHandlerWithoutAnOrgIdVariable_failsClosed_with500() throws Exception {
        given(membershipLookup.roleOf(anyLong(), anyLong())).willReturn(Optional.of(OrganizationRole.OWNER));

        mockMvc.perform(get("/api/v1/organizations/42/unnamed").with(asBob()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    // No controller maps OPTIONS, so Spring answers it with its own HandlerMethod (built in handleNoMatch, which
    // stores no path variables). It runs none of our code: the gate must let it through, not fail closed on it
    @Test
    void aPlainOptionsRequest_isAnsweredBySpring_notRefusedByTheGate() throws Exception {
        mockMvc.perform(options("/api/v1/organizations/42/probe").with(asBob()))
                .andExpect(status().isOk())
                .andExpect(header().string("Allow", containsString("GET")));

        verify(membershipLookup, never()).roleOf(anyLong(), anyLong());
    }

    @Test
    void aPrincipalThatIsNotAnAuthenticatedUser_failsClosed_with500() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/42/probe").with(user("someone")))
                .andExpect(status().isInternalServerError());

        verify(membershipLookup, never()).roleOf(anyLong(), anyLong());
    }
}
