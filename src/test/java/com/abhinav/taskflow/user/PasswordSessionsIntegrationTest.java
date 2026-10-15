package com.abhinav.taskflow.user;

import com.abhinav.taskflow.AdjustableClock;
import com.abhinav.taskflow.Concurrently;
import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.common.mail.CapturingEmailSender;
import com.abhinav.taskflow.common.mail.EmailMessage;
import com.abhinav.taskflow.user.TestLogins.LoginTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 §1.6 + Phase 2 §2.4: password reset and change, and what they do to sessions (decision D7).
 * Reset = account-takeover recovery, so EVERY session ends; change = the caller just proved the password, so every
 * OTHER session ends. Both inside the password's own transaction.
 *
 * <p>Two logins of the same user are two devices: two sessions, two access tokens, two refresh cookies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PasswordSessionsIntegrationTest {

    private static final String USER = "pw-user";
    private static final String NEW_PASSWORD = "brand-new-password-2";
    private static final String SESSIONS = "/api/v1/users/me/sessions";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {};

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    RestTemplateBuilder restTemplateBuilder;

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

    private Long userId;

    @BeforeEach
    void setUp() {
        clock.reset();
        emailSender.clear();
        TestUsers testUsers = new TestUsers(userAccountRepository, passwordEncoder, jdbcTemplate);
        testUsers.deleteAll();
        userId = testUsers.createVerifiedUser(USER).getId();
    }

    // --- Test 6: reset ----------------------------------------------------------------------------------------------

    @Test
    void reset_endsEverySession_onTheNextRequest_andRefreshDiesToo() {
        LoginTokens laptop = TestLogins.login(restTemplate, USER);
        LoginTokens phone = TestLogins.login(restTemplate, USER);

        ResponseEntity<Void> confirmed = confirmReset(requestResetToken(), NEW_PASSWORD);

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        for (LoginTokens device : List.of(laptop, phone)) {
            assertThat(sessionsAs(device).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(refresh(device.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(revokeReasons()).containsExactly("PASSWORD_RESET", "PASSWORD_RESET");
        // The owner can get back in with the new password
        assertThat(TestLogins.login(restTemplate, USER, NEW_PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    void resetToken_worksOnlyOnce() {
        String token = requestResetToken();
        assertThat(confirmReset(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map<String, Object>> again = restTemplate.exchange("/api/v1/auth/password-reset/confirm", HttpMethod.POST,
                new HttpEntity<>(Map.of("token", token, "newPassword", "another-password-3")), JSON_OBJECT);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(again.getBody()).containsEntry("code", "INVALID_TOKEN");
    }

    @Test
    void resetToken_usedTwentyTimesAtOnce_succeedsExactlyOnce() throws Exception {
        String token = requestResetToken();

        List<Integer> statuses = Concurrently.run(20, () -> confirmReset(token, NEW_PASSWORD).getStatusCode().value());

        assertThat(statuses).filteredOn(s -> s == 204).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 400).hasSize(19);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from security_events where user_account_id = ? and event_type = 'PASSWORD_RESET'",
                Integer.class, userId)).isEqualTo(1);
    }

    // --- Test 7: change ---------------------------------------------------------------------------------------------

    @Test
    void change_keepsTheChangingDevice_andEndsEveryOtherSession() {
        LoginTokens laptop = TestLogins.login(restTemplate, USER);
        LoginTokens phone = TestLogins.login(restTemplate, USER);

        ResponseEntity<Map<String, Object>> changed = changePassword(laptop, TestUsers.PASSWORD, NEW_PASSWORD);

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // The laptop just proved the password: no pointless re-login, and its refresh cookie still works
        assertThat(sessionsAs(laptop).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh(laptop.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Every other device is out
        assertThat(sessionsAs(phone).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(phone.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revokeReasonOf(phone)).isEqualTo("PASSWORD_CHANGED");
        assertThat(revokeReasonOf(laptop)).isNull();
    }

    @Test
    void change_withAWrongCurrentPassword_is400_countsTowardLockout_andChangesNothing() {
        LoginTokens laptop = TestLogins.login(restTemplate, USER);

        ResponseEntity<Map<String, Object>> response = changePassword(laptop, "not-the-password", NEW_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .containsEntry("code", "CURRENT_PASSWORD_INCORRECT")
                .containsEntry("field", "currentPassword");
        // Through the AuthenticationManager, like a login: an attacker holding a stolen access token can't use this
        // endpoint to guess the password without tripping the lockout
        assertThat(jdbcTemplate.queryForObject("select failed_login_attempts from user_accounts where id = ?",
                Integer.class, userId)).isEqualTo(1);
        assertThat(sessionsAs(laptop).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(TestLogins.login(restTemplate, USER, TestUsers.PASSWORD).accessToken()).isNotBlank();
    }

    /** Asks for a reset and reads the raw token out of the captured email, exactly as the user would get it. */
    private String requestResetToken() {
        ResponseEntity<Void> requested = restTemplate.postForEntity("/api/v1/auth/password-reset/request",
                Map.of("email", TestUsers.emailOf(USER)), Void.class);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        EmailMessage email = emailSender.latestTo(TestUsers.emailOf(USER)).orElseThrow();
        return email.body().substring(email.body().indexOf("#token=") + "#token=".length());
    }

    private ResponseEntity<Void> confirmReset(String token, String newPassword) {
        return restTemplate.postForEntity("/api/v1/auth/password-reset/confirm",
                Map.of("token", token, "newPassword", newPassword), Void.class);
    }

    private ResponseEntity<Map<String, Object>> changePassword(LoginTokens device, String current, String next) {
        return bearer(device).exchange("/api/v1/users/me/password", HttpMethod.PUT,
                new HttpEntity<>(Map.of("currentPassword", current, "newPassword", next)), JSON_OBJECT);
    }

    private ResponseEntity<String> sessionsAs(LoginTokens device) {
        return bearer(device).getForEntity(SESSIONS, String.class);
    }

    private ResponseEntity<String> refresh(String rawRefreshToken) {
        return restTemplate.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(TestLogins.cookieHeader(rawRefreshToken)), String.class);
    }

    private TestRestTemplate bearer(LoginTokens device) {
        return TestLogins.bearerClient(restTemplateBuilder, restTemplate, device.accessToken());
    }

    private List<String> revokeReasons() {
        return jdbcTemplate.queryForList("select revoke_reason from user_sessions where user_account_id = ? order by id",
                String.class, userId);
    }

    private String revokeReasonOf(LoginTokens device) {
        return jdbcTemplate.queryForObject("select revoke_reason from user_sessions where id = ?",
                String.class, TestLogins.sessionIdOf(device.accessToken()));
    }
}
