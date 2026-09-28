package com.abhinav.taskflow.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    @Query("select u.id from UserAccount u where u.email = :email")
    Optional<Long> findIdByEmail(@Param("email") String email);

    @Modifying
    @Query("""
            update UserAccount u
            set u.failedLoginAttempts = u.failedLoginAttempts + 1
            where u.id = :id
            """)
    int incrementFailedLoginAttempts(@Param("id") Long id);

    @Modifying
    @Query("""
            update UserAccount u
            set u.failedLoginAttempts = 0,
            u.lockedUntil = :lockedUntil
            where u.id = :id
            and u.failedLoginAttempts >= :maxAttempts
    """)
    int lockIfThresholdReached(@Param("id") Long id, @Param("maxAttempts") int maxAttempts, @Param("lockedUntil") Instant lockedUntil);

    @Modifying
    @Query("""
            update UserAccount u
            set u.failedLoginAttempts = 0,
            u.lockedUntil = null
            where u.id = :id
            and (u.failedLoginAttempts > 0 or u.lockedUntil is not null)
    """)
    int resetFailedLoginAttempts(@Param("id") Long id);
}
