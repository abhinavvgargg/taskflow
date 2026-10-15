package com.abhinav.taskflow.user;

import com.abhinav.taskflow.AdjustableClock;
import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.common.mail.CapturingEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.abhinav.taskflow.common.error.ProblemBodies.withoutRequestSpecificFields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 §1.5–§1.6: lockout after repeated failures, and no answer that tells an attacker which emails exist.
 *
 * <p>Lockout settings from application.yml: 5 failures, 15 minutes. The {@link AdjustableClock} moves past the lock
 * instead of waiting for it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class LoginLockoutIntegrationTest {

    private static final String USER = "lock-user";
    private static final String LOCKED = "lock-locked";
    private static final String WRONG_PASSWORD = "definitely-wrong-1";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {};

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CapturingEmailSender emailSender;

    @Autowired
    AdjustableClock clock;

    private TestUsers testUsers;
    private Long userId;

    @BeforeEach
    void setUp() {
        clock.reset();
        emailSender.clear();
        testUsers = new TestUsers(userAccountRepository, passwordEncoder, jdbcTemplate);
        testUsers.deleteAll();
        userId = testUsers.createVerifiedUser(USER).getId();
    }

    // --- Test 8: lockout, and the records that must survive a failed login ------------------------------------------

    @Test
    void fiveWrongPasswords_lockTheAccount_evenAgainstTheRightPassword_untilTheLockEnds() {
        for (int i = 0; i < 4; i++) {
            assertThat(login(USER, WRONG_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // The rollback trap: each failure is a 401 (an exception), yet the counter AND the history must have committed
        assertThat(failedAttempts()).isEqualTo(4);
        assertThat(loginFailedEvents("BAD_CREDENTIALS")).isEqualTo(4);

        assertThat(login(USER, WRONG_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(lockedUntil()).as("the fifth failure locks").isNotNull();
        assertThat(eventCount("ACCOUNT_LOCKED")).isEqualTo(1);

        // Locked means locked: the correct password is refused too, and the refusal is recorded as such
        ResponseEntity<Map<String, Object>> whileLocked = login(USER, TestUsers.PASSWORD);
        assertThat(whileLocked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(whileLocked.getBody()).containsEntry("code", "AUTHENTICATION_FAILED");
        assertThat(loginFailedEvents("ACCOUNT_LOCKED")).isEqualTo(1);

        // The lock ends by itself
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(login(USER, TestUsers.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- Test 10: no enumeration ------------------------------------------------------------------------------------

    @Test
    void unknownEmail_wrongPassword_andLockedAccount_getIndistinguishableAnswers() {
        testUsers.createVerifiedUser(LOCKED);
        testUsers.lockUntil(LOCKED, Instant.now().plus(Duration.ofMinutes(10)));

        ResponseEntity<Map<String, Object>> unknown = login("nobody", TestUsers.PASSWORD);
        ResponseEntity<Map<String, Object>> wrongPassword = login(USER, WRONG_PASSWORD);
        ResponseEntity<Map<String, Object>> locked = login(LOCKED, TestUsers.PASSWORD);

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Same status, same code, same words: "this email exists" and "this account is locked" can't be read off
        assertThat(withoutRequestSpecificFields(wrongPassword.getBody()))
                .isEqualTo(withoutRequestSpecificFields(unknown.getBody()))
                .isEqualTo(withoutRequestSpecificFields(locked.getBody()));
    }

    @Test
    void passwordResetRequest_isAlways202_butOnlyAKnownEmailGetsMail() {
        ResponseEntity<Void> unknown = requestReset("nobody@example.com");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(emailSender.sentMessages()).isEmpty();

        // The positive pair: without it, "no email" could just mean the sender is broken
        ResponseEntity<Void> known = requestReset(TestUsers.emailOf(USER));
        assertThat(known.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(emailSender.latestTo(TestUsers.emailOf(USER))).isPresent();
    }

    private ResponseEntity<Map<String, Object>> login(String username, String password) {
        return restTemplate.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", TestUsers.emailOf(username), "password", password)), JSON_OBJECT);
    }

    private ResponseEntity<Void> requestReset(String email) {
        return restTemplate.postForEntity("/api/v1/auth/password-reset/request", Map.of("email", email), Void.class);
    }

    private int failedAttempts() {
        return jdbcTemplate.queryForObject("select failed_login_attempts from user_accounts where id = ?", Integer.class, userId);
    }

    private Object lockedUntil() {
        return jdbcTemplate.queryForObject("select locked_until from user_accounts where id = ?", Object.class, userId);
    }

    private int eventCount(String eventType) {
        return jdbcTemplate.queryForObject("select count(*) from security_events where user_account_id = ? and event_type = ?",
                Integer.class, userId, eventType);
    }

    private int loginFailedEvents(String reason) {
        return jdbcTemplate.queryForObject("select count(*) from security_events where user_account_id = ? "
                + "and event_type = 'LOGIN_FAILED' and failure_reason = ?", Integer.class, userId, reason);
    }
}
