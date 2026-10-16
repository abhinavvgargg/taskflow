package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.error.ResourceConflictException;
import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.security.UserRole;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OrganizationServiceTest {

    private static final AuthenticatedUser ALICE = new AuthenticatedUser(1L, "alice", UserRole.USER, 10L);

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationMembershipRepository membershipRepository;

    @Mock
    private TenantContext tenantContext;

    @InjectMocks
    private OrganizationService organizationService;

    @Test
    void create_whenDuplicateSlug_throwsConflictWithCode_andSavesNothing() {
        given(organizationRepository.existsOrganizationBySlug("test_slug")).willReturn(true);

        OrganizationRequestDto organizationRequestDto = new OrganizationRequestDto("test_name", "test_slug");

        assertThatThrownBy(() -> organizationService.create(organizationRequestDto, ALICE))
                .isInstanceOfSatisfying(ResourceConflictException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.DUPLICATE_SLUG));
        verify(organizationRepository, never()).save(any());
        verifyNoInteractions(membershipRepository);
    }

    @Test
    void create_whenSlugIsNew_savesTheOrganization_andTheCallerAsOwner() {
        given(organizationRepository.existsOrganizationBySlug("acme-corp")).willReturn(false);
        given(organizationRepository.save(any(Organization.class))).willAnswer(invocation -> {
            Organization saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 51L);   // what the sequence would have assigned
            return saved;
        });

        OrganizationResponseDto created = organizationService.create(new OrganizationRequestDto("Acme Corp", "acme-corp"), ALICE);

        ArgumentCaptor<Organization> savedOrganization = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository, times(1)).save(savedOrganization.capture());
        assertThat(savedOrganization.getValue().getName()).isEqualTo("Acme Corp");
        assertThat(savedOrganization.getValue().getSlug()).isEqualTo("acme-corp");

        ArgumentCaptor<OrganizationMembership> savedMembership = ArgumentCaptor.forClass(OrganizationMembership.class);
        verify(membershipRepository, times(1)).save(savedMembership.capture());
        assertThat(savedMembership.getValue().getOrganizationId()).isEqualTo(51L);
        assertThat(savedMembership.getValue().getUserAccountId()).isEqualTo(ALICE.id());
        assertThat(savedMembership.getValue().getRole()).isEqualTo(OrganizationRole.OWNER);

        assertThat(created.role()).isEqualTo(OrganizationRole.OWNER);
    }

    @Test
    void get_readsTheOrganizationAndRole_fromTheTenantContext() {
        given(tenantContext.organizationId()).willReturn(42L);
        given(tenantContext.role()).willReturn(OrganizationRole.ADMIN);
        given(organizationRepository.findById(42L)).willReturn(Optional.of(Organization.create("Acme Corp", "acme-corp")));

        OrganizationResponseDto organization = organizationService.get();

        assertThat(organization.slug()).isEqualTo("acme-corp");
        assertThat(organization.role()).isEqualTo(OrganizationRole.ADMIN);
        verify(organizationRepository).findById(42L);
    }
}
