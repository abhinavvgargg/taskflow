package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.common.error.ApplicationException;
import com.abhinav.taskflow.common.error.RequestForbiddenException;
import com.abhinav.taskflow.common.error.ResourceInvalidException;
import com.abhinav.taskflow.common.security.AuthenticatedUser;
import com.abhinav.taskflow.common.security.UserRole;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.tenant.TenantContext;
import com.abhinav.taskflow.common.tenant.TenantErrorCode;
import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.Reauthentication;
import com.abhinav.taskflow.user.UserErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The ORDER of a transfer's checks: owner → not yourself → password → the transaction. Each one stops the rest. */
class MembershipWorkflowTest {

    private static final AuthenticatedUser ALICE = new AuthenticatedUser(1L, "alice", UserRole.USER, 10L);
    private static final ClientInfo CLIENT = ClientInfo.UNKNOWN;

    private final TenantContext tenantContext = new TenantContext();
    private final Reauthentication reauthentication = mock(Reauthentication.class);
    private final MembershipService membershipService = mock(MembershipService.class);
    private final MembershipWorkflow workflow = new MembershipWorkflow(tenantContext, reauthentication, membershipService);

    @Test
    void aNonOwner_is403_beforeTheirPasswordIsEvenChecked() {
        tenantContext.enter(42L, OrganizationRole.ADMIN);

        assertThatThrownBy(() -> workflow.transferOwnership(ALICE, new TransferOwnershipRequest(3L, "wrong"), CLIENT))
                .isInstanceOfSatisfying(RequestForbiddenException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(TenantErrorCode.ORGANIZATION_ROLE_REQUIRED));
        // A member's wrong password must never count toward THEIR lockout here
        verifyNoInteractions(reauthentication, membershipService);
    }

    @Test
    void yourselfAsTarget_is400_onTheUserIdField_beforeThePassword() {
        tenantContext.enter(42L, OrganizationRole.OWNER);

        assertThatThrownBy(() -> workflow.transferOwnership(ALICE, new TransferOwnershipRequest(ALICE.id(), "pw"), CLIENT))
                .isInstanceOfSatisfying(ResourceInvalidException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.CANNOT_TRANSFER_TO_SELF);
                    assertThat(ex.getProperties()).containsEntry("field", "userId");
                });
        verifyNoInteractions(reauthentication, membershipService);
    }

    @Test
    void aWrongPassword_stopsTheTransfer() {
        tenantContext.enter(42L, OrganizationRole.OWNER);
        doThrow(new ResourceInvalidException(UserErrorCode.CURRENT_PASSWORD_INCORRECT, "x"))
                .when(reauthentication).confirmPassword(any(), any(), any());

        assertThatThrownBy(() -> workflow.transferOwnership(ALICE, new TransferOwnershipRequest(3L, "wrong"), CLIENT))
                .isInstanceOf(ApplicationException.class);
        verifyNoInteractions(membershipService);
    }

    @Test
    void theRightPassword_thenTheTransaction() {
        tenantContext.enter(42L, OrganizationRole.OWNER);

        workflow.transferOwnership(ALICE, new TransferOwnershipRequest(3L, "right"), CLIENT);

        InOrder inOrder = inOrder(reauthentication, membershipService);
        inOrder.verify(reauthentication).confirmPassword(ALICE, "right", CLIENT);
        inOrder.verify(membershipService).transferOwnership(3L, ALICE);
    }

    @Test
    void theRequest_neverPrintsThePassword() {
        assertThat(new TransferOwnershipRequest(3L, "hunter2-secret").toString())
                .doesNotContain("hunter2-secret")
                .contains("userId=3");
    }
}
