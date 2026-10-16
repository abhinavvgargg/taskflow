package com.abhinav.taskflow.common.tenant;

/**
 * A member's role inside one organization (not the account-wide {@code UserRole}).
 *
 * <p>Declared from the highest to the lowest, and the order is the rule: D6's "you act only on members strictly below
 * you, and grant only roles strictly below yours" is {@link #isAbove}, written once here, so no endpoint can disagree
 * with another about who outranks whom.
 */
public enum OrganizationRole {

    OWNER,
    ADMIN,
    MEMBER;

    /** Strictly above: a role is never above itself, so nobody can create or act on a peer. */
    public boolean isAbove(OrganizationRole other) {
        return ordinal() < other.ordinal();
    }

    /** This role or a higher one: what an endpoint that needs {@code required} accepts. */
    public boolean isAtLeast(OrganizationRole required) {
        return this == required || isAbove(required);
    }
}
