package com.abhinav.taskflow.common.security;

import java.io.Serializable;

/**
 * Who is calling, on every bearer request: read from the access token's claims, never from the database.
 * Deliberately small. No email (not in the token), no password hash, no status flags: those belong to
 * {@link TaskflowPrincipal}, which is only used while a password is being checked.
 */
public record AuthenticatedUser(Long id, String username, UserRole role, Long sessionId) implements Serializable {
}
