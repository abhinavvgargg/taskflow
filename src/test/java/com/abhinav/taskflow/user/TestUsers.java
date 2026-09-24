package com.abhinav.taskflow.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

/**
 * Creates real users in the test database for integration tests.
 *
 * <p>A plain class, not a Spring bean: tests build it from beans they already autowire. Registering it as a
 * bean (@Import / @TestComponent) would change the test context's configuration and start a second
 * application context and a second Postgres container (context caching, Phase 0 testing guide §7).
 *
 * <p>Things with no production API yet are set with SQL, the same way a real admin is created by hand:
 * promoting to ADMIN, and locking an account (§1.5 adds the real lockout code).
 */
public class TestUsers {

    /** The password of every user this class creates. */
    public static final String PASSWORD = "test-password-1";

    // bcrypt is deliberately slow (~50-100 ms per hash), and most tests create several users.
    // Hash the shared password once per test run instead of once per user.
    private static String encodedPassword;

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public TestUsers(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder, JdbcTemplate jdbcTemplate) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The email this class gives a username: {@code alice} → {@code alice@example.com}. */
    public static String emailOf(String username) {
        return username + "@example.com";
    }

    public UserAccount createVerifiedUser(String username) {
        UserAccount user = newUser(username);
        user.markEmailVerified(Instant.now());
        return userAccountRepository.save(user);
    }

    public UserAccount createUnverifiedUser(String username) {
        return userAccountRepository.save(newUser(username));
    }

    public UserAccount createVerifiedAdmin(String username) {
        UserAccount user = createVerifiedUser(username);
        jdbcTemplate.update("update user_accounts set role = 'ADMIN' where id = ?", user.getId());
        return user;
    }

    public void lockUntil(String username, Instant lockedUntil) {
        jdbcTemplate.update("update user_accounts set locked_until = ? where username = ?",
                java.sql.Timestamp.from(lockedUntil), username);
    }

    public void deleteAll() {
        userAccountRepository.deleteAll();
    }

    private UserAccount newUser(String username) {
        return UserAccount.createUserAccount(emailOf(username), username, "Test " + username, encodedPassword(), Instant.now());
    }

    private String encodedPassword() {
        if (encodedPassword == null) {
            encodedPassword = passwordEncoder.encode(PASSWORD);
        }
        return encodedPassword;
    }
}
