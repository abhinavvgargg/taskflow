package com.abhinav.taskflow.user.session;

import com.abhinav.taskflow.common.web.ClientInfo;
import com.abhinav.taskflow.user.event.SecurityEventRecorder;
import com.abhinav.taskflow.user.session.RefreshResult.Reason;
import com.abhinav.taskflow.user.session.RefreshResult.Refreshed;
import com.abhinav.taskflow.user.session.RefreshResult.Rejected;
import com.abhinav.taskflow.user.token.TokenCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SessionService {

    /** Sort for the session list: most recently active first, newest id as the tiebreaker (stable pages). */
    public static final Sort LIST_SORT = Sort.by(Sort.Order.desc("lastRefreshedAt"), Sort.Order.desc("id"));

    private final UserSessionRepository userSessionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenCodec tokenCodec;
    private final SessionProperties sessionProperties;
    private final SecurityEventRecorder securityEventRecorder;

    /** Session and first refresh token commit together, in their own transaction. Only the hash is stored. */
    @Transactional
    public StartedSession start(Long userAccountId, ClientInfo clientInfo, Instant now) {
        UserSession session = userSessionRepository.save(
                UserSession.start(userAccountId, clientInfo, now, sessionProperties.absoluteTtl()));

        String rawToken = tokenCodec.generate();
        Instant expiresAt = refreshTokenExpiry(now, sessionProperties.refreshTokenIdleTtl(), session.getExpiresAt());
        RefreshToken refreshToken = refreshTokenRepository.save(
                RefreshToken.issue(session, tokenCodec.hash(rawToken), now, expiresAt));

        return new StartedSession(session.getId(), rawToken,
                Duration.between(refreshToken.getCreatedAt(), refreshToken.getExpiresAt()));
    }

    /**
     * Rotates a refresh token: consumes the presented one and issues its successor in the same session.
     *
     * <p>Returns a result for every expected outcome and never throws for one: the reuse branch revokes the session and
     * records an event, and an exception here would roll both back (detection that forgets itself).
     *
     * <p>Every write is a conditional UPDATE decided by its row count, so two concurrent refreshes with one cookie
     * produce exactly one winner; the partial unique index uk_refresh_tokens_active backs that up in the database.
     *
     * <p>Must be called OUTSIDE any transaction (SessionWorkflow isn't transactional): the SESSION_ENDED branches mark
     * this transaction rollback-only, and inside a caller's transaction that would end it in UnexpectedRollbackException.
     */
    @Transactional
    public RefreshResult refresh(String rawToken, ClientInfo clientInfo, Instant now) {
        // Postgres keeps microseconds: compare and store the same instant the row will hold
        Instant at = now.truncatedTo(ChronoUnit.MICROS);

        // 1. Shape check before any query: garbage (or no cookie at all) never reaches the database
        if (!tokenCodec.isWellFormed(rawToken)) {
            return new Rejected(Reason.MALFORMED);
        }

        // 2. Find it by hash; the session comes along (its end caps the successor, its account gets the access token)
        Optional<RefreshToken> found = refreshTokenRepository.findWithSessionByTokenHash(tokenCodec.hash(rawToken));
        if (found.isEmpty()) {
            return new Rejected(Reason.UNKNOWN);
        }
        RefreshToken presented = found.get();
        UserSession session = presented.getSession();

        // 3. Claim it: only the request whose UPDATE changes the row may rotate
        if (refreshTokenRepository.consume(presented.getId(), at) == 0) {
            // 4. Lost: expired, a concurrent winner, or theft
            return classifyUnconsumable(presented, session, clientInfo, at);
        }

        // 5. The session must still be live. A conditional UPDATE, never an entity save: a save would write back the
        //    revoked_at = NULL it loaded, over a concurrent "log out everywhere"
        if (userSessionRepository.touch(session.getId(), at) == 0) {
            return sessionEndedUndoingConsume();
        }

        // The CURRENT username and role, so the new access token reflects a role change made since login
        Optional<AccountClaims> account = userSessionRepository.findAccountClaims(session.getUserAccountId());
        if (account.isEmpty()) {
            return sessionEndedUndoingConsume();
        }

        // 6. The successor: same session, expiry capped at the session's absolute end
        String successorRaw = tokenCodec.generate();
        Instant expiresAt = refreshTokenExpiry(at, sessionProperties.refreshTokenIdleTtl(), session.getExpiresAt());
        RefreshToken successor = refreshTokenRepository.save(
                RefreshToken.issue(session, tokenCodec.hash(successorRaw), at, expiresAt));

        return new Refreshed(session.getId(), session.getUserAccountId(), account.get().username(), account.get().role(),
                successorRaw, Duration.between(successor.getCreatedAt(), successor.getExpiresAt()));
    }

    /**
     * Step 4: the conditional consume matched no row. Decides why, in this order:
     * expired (not theft) → consumed within the grace window (a concurrent winner) → consumed earlier (theft).
     */
    private RefreshResult classifyUnconsumable(RefreshToken presented, UserSession session, ClientInfo clientInfo, Instant at) {
        // expires_at never changes, so the entity loaded in step 2 is safe to read here
        if (!presented.getExpiresAt().isAfter(at)) {
            return new Rejected(Reason.EXPIRED);
        }

        // consumed_at DOES change: the entity may have been loaded before the winner committed and still say null.
        // A scalar query reads the committed row instead (each statement sees the latest commit under READ COMMITTED).
        Optional<Instant> consumedAt = refreshTokenRepository.findConsumedAtById(presented.getId());
        if (consumedAt.isEmpty()) {
            // Neither expired nor consumed, yet the update matched nothing: not a state this code can produce
            return new Rejected(Reason.UNKNOWN);
        }

        // Measured from consumed_at (when the winner rotated), not from the token's creation
        if (at.isBefore(consumedAt.get().plus(sessionProperties.reuseGrace()))) {
            return new Rejected(Reason.SUPERSEDED);
        }

        // Theft: the whole family dies (the session, so every refresh token AND every access token carrying its sid).
        // Recorded even if the session had already ended: it's still an attack signal for the owner's history.
        userSessionRepository.revoke(session.getId(), SessionRevokeReason.REFRESH_TOKEN_REUSE, at);
        securityEventRecorder.recordRefreshTokenReused(session.getUserAccountId(), clientInfo);
        return new Rejected(Reason.REUSED);
    }

    /**
     * The token was claimed in step 3, but its session (or account) turned out to be gone. Leaving the token consumed
     * would make any later presentation of it look like theft (REUSED) although the client did nothing wrong: e.g. a
     * phone app retrying after a password reset revoked every session. So this transaction is rolled back, which
     * undoes the consume, and the rejection is still RETURNED (nothing else in this branch needed to commit).
     *
     * <p>Rolling back also covers the race where the session is revoked between the token lookup and the consume,
     * which an "is the session live?" check before consuming could not.
     */
    private RefreshResult sessionEndedUndoingConsume() {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return new Rejected(Reason.SESSION_ENDED);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // §2.4: revocation and listing. Every revocation is ONE conditional UPDATE (no entity is loaded), and each one
    // requires "not revoked yet", so a second revocation changes nothing and the first reason is kept.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Logout: revokes the session the refresh cookie belongs to, whatever the token's own state (an old, consumed
     * cookie still ends its family). Unknown, malformed or missing tokens do nothing: logout always succeeds.
     * No event: a plain logout of this device isn't security-relevant, and the session row already records it.
     *
     * @return whether a live session was revoked (for the log; the client always gets 204)
     */
    @Transactional
    public boolean revokeByRefreshToken(String rawToken, Instant now) {
        if (!tokenCodec.isWellFormed(rawToken)) {
            return false;
        }
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        return refreshTokenRepository.findSessionIdByTokenHash(tokenCodec.hash(rawToken))
                .map(sessionId -> userSessionRepository.revoke(sessionId, SessionRevokeReason.LOGOUT, at) == 1)
                .orElse(false);
    }

    /**
     * Revokes one of the caller's sessions, chosen from the session list. The account id is in the UPDATE's WHERE
     * clause: someone else's session id matches no row, exactly like an id that doesn't exist (IDOR by construction).
     *
     * @return whether a row was revoked; false means "not found" to the caller (unknown, not yours, or already ended)
     */
    @Transactional
    public boolean revokeOne(Long userAccountId, Long sessionId, ClientInfo clientInfo, Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        if (userSessionRepository.revokeOwned(sessionId, userAccountId, SessionRevokeReason.REVOKED_BY_USER, at) == 0) {
            return false;
        }
        securityEventRecorder.recordSessionRevoked(userAccountId, clientInfo);
        return true;
    }

    /**
     * "Log out everywhere": the panic button. Revokes every live session of the account, INCLUDING the caller's own
     * (a user who presses it suspects compromise; sparing this device would be a guess that it's clean).
     * The event is recorded once, in the same transaction as the revocation.
     */
    @Transactional
    public int logOutEverywhere(Long userAccountId, ClientInfo clientInfo, Instant now) {
        int revoked = userSessionRepository.revokeAll(userAccountId, SessionRevokeReason.LOGOUT_ALL, now.truncatedTo(ChronoUnit.MICROS));
        securityEventRecorder.recordAllSessionsRevoked(userAccountId, clientInfo);
        return revoked;
    }

    /**
     * Revokes every session of the account, for a password reset. MANDATORY: it must commit with the password change
     * that causes it, or not at all; called outside a transaction it throws instead of committing on its own.
     * (Callers must go through the Spring proxy: a call via {@code this} would skip the MANDATORY check.)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAll(Long userAccountId, SessionRevokeReason reason, Instant now) {
        return userSessionRepository.revokeAll(userAccountId, reason, now.truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Revokes every session of the account except one, for a password change (the session that proved the current
     * password keeps working). MANDATORY, like {@link #revokeAll}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAllExcept(Long userAccountId, Long exceptSessionId, SessionRevokeReason reason, Instant now) {
        // "id <> NULL" is UNKNOWN in SQL, never true: a null here would silently revoke nothing at all
        Assert.notNull(exceptSessionId, "exceptSessionId must not be null");
        return userSessionRepository.revokeAllExcept(userAccountId, exceptSessionId, reason, now.truncatedTo(ChronoUnit.MICROS));
    }

    /** The caller's live sessions (not revoked, not past their absolute end), marking the one making this request. */
    @Transactional(readOnly = true)
    public Page<SessionResponse> listLive(Long userAccountId, Long currentSessionId, Pageable pageable, Instant now) {
        return userSessionRepository.findLive(userAccountId, now.truncatedTo(ChronoUnit.MICROS), pageable)
                .map(summary -> SessionResponse.from(summary, currentSessionId));
    }

    /** The earlier of now + idle lifetime and the session's absolute end. */
    static Instant refreshTokenExpiry(Instant now, Duration idleTtl, Instant sessionExpiresAt) {
        Instant idleExpiry = now.plus(idleTtl);
        return idleExpiry.isBefore(sessionExpiresAt) ? idleExpiry : sessionExpiresAt;
    }
}
