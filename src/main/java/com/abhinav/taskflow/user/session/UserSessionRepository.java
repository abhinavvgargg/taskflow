package com.abhinav.taskflow.user.session;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    // On the path of every request: selects a boolean, never an entity
    @Query("""
            select case when count(s) > 0 then true else false end
              from UserSession s
             where s.id = :sessionId
               and s.userAccountId = :userAccountId
               and s.revokedAt is null
               and s.expiresAt > :now
            """)
    boolean isLive(@Param("sessionId") Long sessionId,
                   @Param("userAccountId") Long userAccountId,
                   @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update UserSession s
               set s.lastRefreshedAt = :now
             where s.id = :sessionId
               and s.revokedAt is null
               and s.expiresAt > :now
            """)
    int touch(@Param("sessionId") Long sessionId,
              @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update UserSession s
               set s.revokedAt = :now,
                   s.revokeReason = :reason
             where s.id = :sessionId
               and s.revokedAt is null
               and s.expiresAt > :now
            """)
    int revoke(@Param("sessionId") Long sessionId,
               @Param("reason") SessionRevokeReason reason,
               @Param("now") Instant now);

    // The account id in the WHERE clause is the IDOR guard: another user's session id matches 0 rows
    @Modifying(flushAutomatically = true)
    @Query("""
            update UserSession s
               set s.revokedAt = :now,
                   s.revokeReason = :reason
             where s.id = :sessionId
               and s.userAccountId = :userAccountId
               and s.revokedAt is null
               and s.expiresAt > :now
            """)
    int revokeOwned(@Param("sessionId") Long sessionId,
                    @Param("userAccountId") Long userAccountId,
                    @Param("reason") SessionRevokeReason reason,
                    @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update UserSession s
               set s.revokedAt = :now,
                   s.revokeReason = :reason
             where s.userAccountId = :userAccountId
               and s.revokedAt is null
            """)
    int revokeAll(@Param("userAccountId") Long userAccountId,
                  @Param("reason") SessionRevokeReason reason,
                  @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update UserSession s
               set s.revokedAt = :now,
                   s.revokeReason = :reason
             where s.userAccountId = :userAccountId
               and s.id <> :exceptSessionId
               and s.revokedAt is null
            """)
    int revokeAllExcept(@Param("userAccountId") Long userAccountId,
                        @Param("exceptSessionId") Long exceptSessionId,
                        @Param("reason") SessionRevokeReason reason,
                        @Param("now") Instant now);

    @Query(value = """
            select new com.abhinav.taskflow.user.session.SessionSummary(
                       s.id, s.createdAt, s.lastRefreshedAt, s.expiresAt, s.ipAddress, s.userAgent)
              from UserSession s
             where s.userAccountId = :userAccountId
               and s.revokedAt is null
               and s.expiresAt > :now
             order by s.lastRefreshedAt desc, s.id desc
            """,
            countQuery = """
            select count(s)
              from UserSession s
             where s.userAccountId = :userAccountId
               and s.revokedAt is null
               and s.expiresAt > :now
            """)
    Page<SessionSummary> findLive(@Param("userAccountId") Long userAccountId,
                                  @Param("now") Instant now,
                                  Pageable pageable);
}