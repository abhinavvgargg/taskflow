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
}
