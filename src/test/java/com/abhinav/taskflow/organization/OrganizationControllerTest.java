package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.web.PageableFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.BDDMockito.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrganizationController.class)
public class OrganizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrganizationService organizationService;

    @MockitoBean
    private PageableFactory pageableFactory;

    @Test
    void getById_returnsOrganizationJson() throws Exception {
        var dto = new OrganizationResponseDto(7L, "Acme Corp", "acme-corp", "system",
                Instant.parse("2026-09-01T10:00:00Z"));
        given(organizationService.findOrganizationById(7L)).willReturn(dto);

        mockMvc.perform(get("/api/v1/organizations/{id}", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.slug").value("acme-corp"))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void create_withInvalidBody_returns400ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/organizations")
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
    @Test
    void create_withValidBody_returns201WithLocation() throws Exception {
        var request = new OrganizationRequestDto("Acme Corp", "acme-corp");
        given(organizationService.createOrganization(request)).willReturn(
                new OrganizationResponseDto(51L, "Acme Corp", "acme-corp", "system",
                        Instant.parse("2026-09-01T10:00:00Z")));

        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Acme Corp", "slug": "acme-corp"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/organizations/51")))
                .andExpect(jsonPath("$.id").value(51));
    }
}
