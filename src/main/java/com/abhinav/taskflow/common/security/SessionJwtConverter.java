package com.abhinav.taskflow.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.util.StringUtils;

/**
 * Turns a decoded, validated access token into the request's Authentication.
 *
 * <p>It runs only after the decoder has checked the signature, expiry, issuer and audience, so the database is
 * asked about genuine, unexpired tokens only. Every rejection is an {@link InvalidBearerTokenException}: a 401
 * through the entry point. Its message is for the trace log; the response never shows it.
 *
 * <p>Not a Spring bean on purpose: Boot adds every Converter bean to MVC's conversion service.
 */
public class SessionJwtConverter implements Converter<Jwt, TaskflowAuthenticationToken> {

    private final SessionStatus sessionStatus;

    public SessionJwtConverter(SessionStatus sessionStatus) {
        this.sessionStatus = sessionStatus;
    }

    @Override
    public TaskflowAuthenticationToken convert(Jwt jwt) {
        long userId = requireId(jwt.getSubject(), "sub");
        long sessionId = requireId(jwt.getClaimAsString(TaskflowClaims.SESSION_ID), TaskflowClaims.SESSION_ID);
        String username = requireText(jwt.getClaimAsString(TaskflowClaims.USERNAME), TaskflowClaims.USERNAME);
        UserRole role = requireRole(jwt.getClaimAsString(TaskflowClaims.ROLE));

        // Last, because it's the only check that costs a query
        if (!sessionStatus.isLive(sessionId, userId)) {
            throw new InvalidBearerTokenException("Session is not live");
        }

        return new TaskflowAuthenticationToken(new AuthenticatedUser(userId, username, role, sessionId));
    }

    private static long requireId(String value, String claim) {
        try {
            return Long.parseLong(requireText(value, claim));
        } catch (NumberFormatException e) {
            throw new InvalidBearerTokenException("Claim '" + claim + "' is not a numeric id");
        }
    }

    private static String requireText(String value, String claim) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidBearerTokenException("Claim '" + claim + "' is missing");
        }
        return value;
    }

    /** An unknown role is a rejected token, never a default: a token can't grant a role the enum doesn't know. */
    private static UserRole requireRole(String value) {
        try {
            return UserRole.valueOf(requireText(value, TaskflowClaims.ROLE));
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Claim 'role' has an unknown value");
        }
    }
}
