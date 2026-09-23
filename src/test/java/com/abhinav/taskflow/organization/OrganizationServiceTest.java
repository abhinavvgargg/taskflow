package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.error.ResourceConflictException;
import com.abhinav.taskflow.common.error.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OrganizationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private OrganizationService organizationService;

    @Test
    void findById_whenMissing_throwsNotFoundWithCode() {
        given(organizationRepository.findById(42L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> organizationService.findOrganizationById(42L))
                .isInstanceOfSatisfying(ResourceNotFoundException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.ORGANIZATION_NOT_FOUND);
                    assertThat(ex.getProperties()).containsEntry("id", 42L);
                });
    }

    @Test
    void save_whenDuplicateSlug_throwsConflictWithCode() {
        given(organizationRepository.existsOrganizationBySlug("test_slug")).willReturn(true);

        OrganizationRequestDto organizationRequestDto = new OrganizationRequestDto("test_name", "test_slug");

        assertThatThrownBy(() -> organizationService.createOrganization(organizationRequestDto))
                .isInstanceOfSatisfying(ResourceConflictException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.DUPLICATE_SLUG);
                });
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void create_whenSlugIsNew_savesMappedEntityOnce() {
        given(organizationRepository.existsOrganizationBySlug("acme-corp")).willReturn(false);
        given(organizationRepository.save(any(Organization.class)))
                .willAnswer(invocation -> invocation.getArgument(0));   // echo back what was saved

        organizationService.createOrganization(new OrganizationRequestDto("Acme Corp", "acme-corp"));

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Acme Corp");
        assertThat(saved.getValue().getSlug()).isEqualTo("acme-corp");
    }
}
