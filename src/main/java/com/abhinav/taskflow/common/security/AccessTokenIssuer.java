package com.abhinav.taskflow.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AccessTokenIssuer {

    private final JwtSigningKey signingKey;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public IssuedAccessToken issue(long userId, String username, String role, long sessionId, Instant now) {
        Assert.hasText(username, "username must not be blank");
        Assert.hasText(role, "role must not be blank");
        Assert.notNull(now, "now must not be null");

        // A JWT stores iat/exp in whole seconds; truncating keeps the returned expiresAt equal to the token's exp
        Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(jwtProperties.accessTokenTtl());

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(signingKey.keyId())
                .type("JWT")
                .build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .subject(Long.toString(userId))
                .claim(TaskflowClaims.SESSION_ID, Long.toString(sessionId))
                .claim(TaskflowClaims.USERNAME, username)
                .claim(TaskflowClaims.ROLE, role)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(token, issuedAt, expiresAt);
    }
}
