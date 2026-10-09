package com.abhinav.taskflow.common.security;

/**
 * "Is this login still allowed?", asked on every bearer request after the token itself checked out.
 * An interface here, implemented in user.session, so common never imports a feature (like ErrorCode).
 */
public interface SessionStatus {

    /**
     * Whether session {@code sessionId} belongs to account {@code userAccountId}, isn't revoked and hasn't
     * passed its absolute expiry. Database errors propagate: an outage is a 500, never a 401.
     */
    boolean isLive(long sessionId, long userAccountId);
}
