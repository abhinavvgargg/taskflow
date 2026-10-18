package com.abhinav.taskflow.common.tenant;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** D6's "strictly below" rule, all 9 pairs: a role is never above itself, so nobody can create or act on a peer. */
class OrganizationRoleTest {

    @ParameterizedTest(name = "{0} isAbove {1} -> {2}; isAtLeast -> {3}")
    @CsvSource(delimiter = '|', textBlock = """
            OWNER  | OWNER  | false | true
            OWNER  | ADMIN  | true  | true
            OWNER  | MEMBER | true  | true
            ADMIN  | OWNER  | false | false
            ADMIN  | ADMIN  | false | true
            ADMIN  | MEMBER | true  | true
            MEMBER | OWNER  | false | false
            MEMBER | ADMIN  | false | false
            MEMBER | MEMBER | false | true
            """)
    void isAbove_isStrict_andIsAtLeast_includesEqual(OrganizationRole role, OrganizationRole other,
                                                     boolean above, boolean atLeast) {
        assertThat(role.isAbove(other)).isEqualTo(above);
        assertThat(role.isAtLeast(other)).isEqualTo(atLeast);
    }

    // D6 for role changes: the member AND the new role must both be strictly below the caller. 3 callers x 3 targets
    // x 2 grantable roles (OWNER is never granted: it moves only by transfer, and validation refuses it)
    @ParameterizedTest(name = "{0} gives a {1} the role {2} -> {3}")
    @CsvSource(delimiter = '|', textBlock = """
            OWNER  | OWNER  | ADMIN  | false
            OWNER  | OWNER  | MEMBER | false
            OWNER  | ADMIN  | ADMIN  | true
            OWNER  | ADMIN  | MEMBER | true
            OWNER  | MEMBER | ADMIN  | true
            OWNER  | MEMBER | MEMBER | true
            ADMIN  | OWNER  | ADMIN  | false
            ADMIN  | OWNER  | MEMBER | false
            ADMIN  | ADMIN  | ADMIN  | false
            ADMIN  | ADMIN  | MEMBER | false
            ADMIN  | MEMBER | ADMIN  | false
            ADMIN  | MEMBER | MEMBER | true
            MEMBER | OWNER  | ADMIN  | false
            MEMBER | OWNER  | MEMBER | false
            MEMBER | ADMIN  | ADMIN  | false
            MEMBER | ADMIN  | MEMBER | false
            MEMBER | MEMBER | ADMIN  | false
            MEMBER | MEMBER | MEMBER | false
            """)
    void mayChangeRole_needsTheMemberAndTheNewRoleBelowTheCaller(OrganizationRole caller, OrganizationRole target,
                                                                  OrganizationRole newRole, boolean allowed) {
        assertThat(caller.mayChangeRole(target, newRole)).isEqualTo(allowed);
    }

    @ParameterizedTest(name = "{0} removes a {1} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            OWNER  | OWNER  | false
            OWNER  | ADMIN  | true
            OWNER  | MEMBER | true
            ADMIN  | OWNER  | false
            ADMIN  | ADMIN  | false
            ADMIN  | MEMBER | true
            MEMBER | OWNER  | false
            MEMBER | ADMIN  | false
            MEMBER | MEMBER | false
            """)
    void mayRemove_onlyMembersStrictlyBelow(OrganizationRole caller, OrganizationRole target, boolean allowed) {
        assertThat(caller.mayRemove(target)).isEqualTo(allowed);
    }
}
