package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.error.ApplicationException;
import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.security.UserRole;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.tenant.TenantContext;
import com.abhinav.taskflow.common.tenant.TenantErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * The service's own decisions, with the database mocked: what it writes, what it doesn't, and which error each
 * "0 rows" becomes. The real statements and races are in MembershipManagementIntegrationTest.
 */
class MembershipServiceTest {

    private static final long ACME = 42L;
    private static final long MIA = 3L;
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final AuthenticatedUser ALICE = new AuthenticatedUser(1L, "alice", UserRole.USER, 10L);

    private final OrganizationMembershipRepository repository = mock(OrganizationMembershipRepository.class);
    private final TenantContext tenantContext = new TenantContext();
    private final MembershipService service =
            new MembershipService(repository, tenantContext, Clock.fixed(NOW, ZoneOffset.UTC));

    private final MemberResponseDto miaAsAdmin = new MemberResponseDto(MIA, "mia", "Mia", OrganizationRole.ADMIN, NOW);

    @BeforeEach
    void miaIsAMember() {
        given(repository.findRole(ACME, MIA)).willReturn(Optional.of(OrganizationRole.MEMBER));
    }

    @Test
    void changeRole_writesWithTheRoleItRead_setsTheAuditColumns_andReturnsTheStoredRow() {
        tenantContext.enter(ACME, OrganizationRole.OWNER);
        given(repository.changeRole(ACME, MIA, OrganizationRole.MEMBER, OrganizationRole.ADMIN, NOW, "alice")).willReturn(1);
        given(repository.findMember(ACME, MIA)).willReturn(Optional.of(miaAsAdmin));

        assertThat(service.changeRole(MIA, OrganizationRole.ADMIN, ALICE)).isEqualTo(miaAsAdmin);

        InOrder inOrder = inOrder(repository);
        inOrder.verify(repository).changeRole(ACME, MIA, OrganizationRole.MEMBER, OrganizationRole.ADMIN, NOW, "alice");
        inOrder.verify(repository).findMember(ACME, MIA);   // read back AFTER the write
    }

    @Test
    void changeRole_toTheCurrentRole_writesNothing() {
        tenantContext.enter(ACME, OrganizationRole.ADMIN);   // allowed: MEMBER -> MEMBER is below an admin
        given(repository.findMember(ACME, MIA)).willReturn(Optional.of(miaAsAdmin));

        service.changeRole(MIA, OrganizationRole.MEMBER, ALICE);

        verify(repository, never()).changeRole(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void changeRole_whenTheRuleSaysNo_is403_andWritesNothing() {
        tenantContext.enter(ACME, OrganizationRole.ADMIN);   // an admin can't create a peer

        assertThatThrownBy(() -> service.changeRole(MIA, OrganizationRole.ADMIN, ALICE))
                .isInstanceOfSatisfying(ApplicationException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.CANNOT_MANAGE_MEMBER));
        verify(repository, never()).changeRole(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void changeRole_ofTheOwnerThemselves_is403_notALeftToTheDatabase() {
        tenantContext.enter(ACME, OrganizationRole.OWNER);
        given(repository.findRole(ACME, ALICE.id())).willReturn(Optional.of(OrganizationRole.OWNER));

        assertThatThrownBy(() -> service.changeRole(ALICE.id(), OrganizationRole.ADMIN, ALICE))
                .isInstanceOfSatisfying(ApplicationException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.CANNOT_MANAGE_MEMBER));
        verify(repository, never()).changeRole(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void changeRole_whenTheRowChangedMeanwhile_is409() {
        tenantContext.enter(ACME, OrganizationRole.OWNER);
        given(repository.changeRole(anyLong(), anyLong(), any(), any(), any(), any())).willReturn(0);

        assertThatThrownBy(() -> service.changeRole(MIA, OrganizationRole.ADMIN, ALICE))
                .isInstanceOfSatisfying(ApplicationException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.MEMBERSHIP_CHANGED));
    }

    @Test
    void aTargetWhoIsNotAMember_is404MemberNotFound() {
        tenantContext.enter(ACME, OrganizationRole.OWNER);
        given(repository.findRole(ACME, 99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(99L))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.MEMBER_NOT_FOUND);
                    assertThat(ex.getProperties()).containsEntry("userId", 99L);
                });
    }

    @Test
    void remove_deletesWithTheRoleItRead_and0RowsIs409() {
        tenantContext.enter(ACME, OrganizationRole.ADMIN);
        given(repository.remove(ACME, MIA, OrganizationRole.MEMBER)).willReturn(1, 0);

        service.remove(MIA);
        assertThatThrownBy(() -> service.remove(MIA))
                .isInstanceOfSatisfying(ApplicationException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.MEMBERSHIP_CHANGED));
    }

    @Test
    void leave_asTheOwner_is409_andAfterBeingRemovedMeanwhile_is404() {
        tenantContext.enter(ACME, OrganizationRole.OWNER);
        given(repository.leave(ACME, ALICE.id())).willReturn(0);
        given(repository.findRole(ACME, ALICE.id())).willReturn(Optional.of(OrganizationRole.OWNER), Optional.empty());

        assertThatThrownBy(() -> service.leave(ALICE))
                .isInstanceOfSatisfying(ApplicationException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.OWNER_CANNOT_LEAVE));
        assertThatThrownBy(() -> service.leave(ALICE))
                .isInstanceOfSatisfying(ApplicationException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(TenantErrorCode.ORGANIZATION_NOT_FOUND));
    }

    @Test
    void transfer_demotesFirst_thenPromotes() {
        tenantContext.enter(ACME, OrganizationRole.OWNER);
        given(repository.demoteOwner(ACME, ALICE.id(), NOW, "alice")).willReturn(1);
        given(repository.promoteToOwner(ACME, MIA, NOW, "alice")).willReturn(1);

        service.transferOwnership(MIA, ALICE);

        InOrder inOrder = inOrder(repository);
        inOrder.verify(repository).demoteOwner(ACME, ALICE.id(), NOW, "alice");
        inOrder.verify(repository).promoteToOwner(ACME, MIA, NOW, "alice");
    }

    @Test
    void transfer_whenNoLongerTheOwner_is409_andPromotesNobody() {
        tenantContext.enter(ACME, OrganizationRole.OWNER);
        given(repository.demoteOwner(anyLong(), anyLong(), any(), any())).willReturn(0);

        assertThatThrownBy(() -> service.transferOwnership(MIA, ALICE))
                .isInstanceOfSatisfying(ApplicationException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.MEMBERSHIP_CHANGED));
        verify(repository, never()).promoteToOwner(anyLong(), anyLong(), any(), any());
    }

    @Test
    void transfer_toSomeoneWhoIsNotAMember_is404() {
        tenantContext.enter(ACME, OrganizationRole.OWNER);
        given(repository.demoteOwner(anyLong(), anyLong(), any(), any())).willReturn(1);
        given(repository.promoteToOwner(anyLong(), anyLong(), any(), any())).willReturn(0);

        assertThatThrownBy(() -> service.transferOwnership(99L, ALICE))
                .isInstanceOfSatisfying(ApplicationException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.MEMBER_NOT_FOUND));
    }
}
