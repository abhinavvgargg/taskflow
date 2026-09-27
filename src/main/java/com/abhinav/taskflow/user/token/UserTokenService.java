package com.abhinav.taskflow.user.token;

import com.abhinav.taskflow.common.error.ResourceInvalidException;
import com.abhinav.taskflow.user.UserAccount;
import com.abhinav.taskflow.user.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class UserTokenService {

    private final UserTokenRepository userTokenRepository;
    private final TokenCodec tokenCodec;
    private final Clock clock;
    private final TokenProperties tokenProperties;

    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedToken issue (UserAccount userAccount, TokenPurpose purpose) {

        Instant now = clock.instant();
        Instant expiresAt = now.plus(tokenProperties.ttl(purpose));

        userTokenRepository.revokeActive(userAccount, purpose, now);

        String raw = tokenCodec.generate();
        userTokenRepository.saveAndFlush(new UserToken(userAccount, purpose, tokenCodec.hash(raw), expiresAt));
        return new IssuedToken(raw, expiresAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UserToken consume(String rawToken, TokenPurpose purpose) {
        if (!tokenCodec.isWellFormed(rawToken)) {
            throw new ResourceInvalidException(UserErrorCode.INVALID_TOKEN, "Token is invalid");
        }
        String hash = tokenCodec.hash(rawToken);
        if (userTokenRepository.consume(hash, purpose, clock.instant()) == 0) {
            throw new ResourceInvalidException(UserErrorCode.INVALID_TOKEN, "Token is invalid");
        }
        return userTokenRepository.findWithUserByTokenHash(hash).orElseThrow(() -> new ResourceInvalidException(UserErrorCode.INVALID_TOKEN, "Token is invalid"));
    }
}
