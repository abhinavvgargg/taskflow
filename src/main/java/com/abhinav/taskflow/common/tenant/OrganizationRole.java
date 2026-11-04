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

    /**
     * May a caller with this role give a member who holds {@code targetRole} the role {@code newRole}? Two comparisons:
     * the member AND the role they'd get must both be strictly below the caller. With only the second, an owner could
     * "demote" themselves (OWNER → ADMIN is a role below OWNER), and only the database's owner trigger would stop it.
     */
    public boolean mayChangeRole(OrganizationRole targetRole, OrganizationRole newRole) {
        return isAbove(targetRole) && isAbove(newRole);
    }

    /** May a caller with this role remove a member who holds {@code targetRole}? Never yourself or a peer. */
    public boolean mayRemove(OrganizationRole targetRole) {
        return isAbove(targetRole);
    }

    /** May a caller with this role invite someone as {@code role}? Strictly below: owners invite admins and members, admins only members. */
    public boolean mayInvite(OrganizationRole role) {
        return isAbove(role);
    }
}
