package com.abhinav.taskflow.common.tenant;

import com.abhinav.taskflow.common.error.RequestForbiddenException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A plain unit test: a new TenantContext() is what Spring creates once per request. */
class TenantContextTest {

    private final TenantContext context = new TenantContext();

    @Test
    void enter_thenRead_returnsWhatTheGateEntered() {
        context.enter(42L, OrganizationRole.ADMIN);

        assertThat(context.isEntered()).isTrue();
        assertThat(context.organizationId()).isEqualTo(42L);
        assertThat(context.role()).isEqualTo(OrganizationRole.ADMIN);
    }

    @Test
    void aSecondEnter_inTheSameRequest_throws_andKeepsTheFirstTenant() {
        context.enter(42L, OrganizationRole.MEMBER);

        assertThatThrownBy(() -> context.enter(43L, OrganizationRole.OWNER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(context.organizationId()).isEqualTo(42L);
        assertThat(context.role()).isEqualTo(OrganizationRole.MEMBER);
    }

    @Test
    void readingBeforeEnter_throws() {
        assertThat(context.isEntered()).isFalse();
        assertThatThrownBy(context::organizationId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(context::role).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> context.requireAtLeast(OrganizationRole.MEMBER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requireAtLeast_passesForTheSameOrAHigherRole() {
        context.enter(42L, OrganizationRole.ADMIN);

        assertThatNoException().isThrownBy(() -> context.requireAtLeast(OrganizationRole.ADMIN));
        assertThatNoException().isThrownBy(() -> context.requireAtLeast(OrganizationRole.MEMBER));
    }

    @Test
    void requireAtLeast_isA403ForALowerRole() {
        context.enter(42L, OrganizationRole.ADMIN);

        assertThatThrownBy(() -> context.requireAtLeast(OrganizationRole.OWNER))
                .isInstanceOfSatisfying(RequestForbiddenException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(TenantErrorCode.ORGANIZATION_ROLE_REQUIRED));
    }
}
