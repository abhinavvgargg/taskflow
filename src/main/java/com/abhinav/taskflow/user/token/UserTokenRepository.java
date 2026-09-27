package com.abhinav.taskflow.user.token;

import com.abhinav.taskflow.user.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    @Modifying(flushAutomatically = true)
    @Query("""
            update UserToken t
            set t.revokedAt = :now
            where t.userAccount = :userAccount
            and t.purpose = :purpose
            and t.consumedAt is null
            and t.revokedAt is null
            """)
    int revokeActive(@Param("userAccount") UserAccount userAccount,
                     @Param("purpose") TokenPurpose purpose,
                     @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update UserToken t
               set t.consumedAt = :now
             where t.tokenHash = :hash
               and t.purpose = :purpose
               and t.consumedAt is null
               and t.revokedAt is null
               and t.expiresAt > :now
            """)
    int consume(@Param("hash") String hash,
                @Param("purpose") TokenPurpose purpose,
                @Param("now") Instant now);

    @Query("select t from UserToken t join fetch t.userAccount where t.tokenHash = :hash")
    Optional<UserToken> findWithUserByTokenHash(@Param("hash") String hash);

    @Modifying
    @Query("delete from UserToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
