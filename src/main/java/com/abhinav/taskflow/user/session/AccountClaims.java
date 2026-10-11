package com.abhinav.taskflow.user.session;

import com.abhinav.taskflow.common.security.UserRole;

/** The account's CURRENT username and role, read on every refresh so a role change reaches tokens within 15 minutes. */
public record AccountClaims(String username, UserRole role) {
}
