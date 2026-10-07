package com.abhinav.taskflow.user.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // The session comes along: refresh needs its expiresAt (successor cap) and userAccountId (new access token)
    @Query("select t from RefreshToken t join fetch t.session where t.tokenHash = :hash")
    Optional<RefreshToken> findWithSessionByTokenHash(@Param("hash") String hash);

    // Exactly one of N concurrent refreshes with the same token gets 1; the rest get 0
    @Modifying(flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.consumedAt = :now
             where t.id = :id
               and t.consumedAt is null
               and t.expiresAt > :now
            """)
    int consume(@Param("id") Long id,
                @Param("now") Instant now);

    // A scalar query reads the committed row, not the entity loaded before consume() lost the race
    @Query("select t.consumedAt from RefreshToken t where t.id = :id")
    Optional<Instant> findConsumedAtById(@Param("id") Long id);

    // Logout: no entity is loaded, and an unknown token is simply empty
    @Query("select t.session.id from RefreshToken t where t.tokenHash = :hash")
    Optional<Long> findSessionIdByTokenHash(@Param("hash") String hash);
}