package com.abhinav.taskflow.user.session;

import com.abhinav.taskflow.AdjustableClock;
import com.abhinav.taskflow.Concurrently;
import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.user.TestLogins;
import com.abhinav.taskflow.user.TestLogins.LoginTokens;
import com.abhinav.taskflow.user.TestUsers;
import com.abhinav.taskflow.user.UserAccount;
import com.abhinav.taskflow.user.UserAccountRepository;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 §2.3: refresh-token rotation and reuse detection (decision D6), and the dead-session rollback (D13).
 *
 * <p>The reuse grace is 10 s (application.yml). Tests that need "later than the grace" move the shared
 * {@link AdjustableClock} instead of sleeping; it's reset before each test.
 *
 * <p>The cookie is sent by hand on every call ({@link TestLogins#cookieHeader}): the test client has no cookie jar, so
 * a test decides exactly which token each request presents (the same reason the Postman collection disables its jar).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RefreshIntegrationTest {

    private static final String USER = "refresh-user";
    private static final Duration PAST_THE_GRACE = Duration.ofSeconds(11);
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
    AdjustableClock clock;

    private Long userId;

    @BeforeEach
    void setUp() {
        clock.reset();
        TestUsers testUsers = new TestUsers(userAccountRepository, passwordEncoder, jdbcTemplate);
        testUsers.deleteAll();
        UserAccount user = testUsers.createVerifiedUser(USER);
        userId = user.getId();
    }

    // --- Test 2: rotation, then reuse after the grace kills the whole family ----------------------------------------

    @Test
    void reusingARotatedToken_afterTheGrace_revokesTheSession_andKillsTheWinnersTokensToo() {
        LoginTokens login = TestLogins.login(restTemplate, USER);
        long sessionId = TestLogins.sessionIdOf(login.accessToken());

        // Rotation: a new cookie and a new access token, same session
        ResponseEntity<Map<String, Object>> rotated = refresh(login.refreshToken());
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String successor = TestLogins.refreshTokenIn(rotated).orElseThrow();
        String newAccessToken = (String) rotated.getBody().get("accessToken");
        assertThat(successor).isNotEqualTo(login.refreshToken());
        assertThat(TestLogins.sessionIdOf(newAccessToken)).isEqualTo(sessionId);

        // The OLD cookie, presented after the grace: only a copy of it can do that, so this is theft
        clock.advance(PAST_THE_GRACE);
        ResponseEntity<Map<String, Object>> reused = refresh(login.refreshToken());

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reused.getBody()).containsEntry("code", "INVALID_REFRESH_TOKEN");
        assertThat(TestLogins.refreshSetCookie(reused)).hasValueSatisfying(header -> assertThat(header).contains("Max-Age=0"));
        assertThat(revokeReason(sessionId)).isEqualTo("REFRESH_TOKEN_REUSE");
        assertThat(eventCount("REFRESH_TOKEN_REUSED")).isEqualTo(1);

        // The server can't tell which copy is the thief's, so the winner's tokens die with the family
        assertThat(refresh(successor).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(TestLogins.bearerClient(restTemplateBuilder, restTemplate, newAccessToken)
                .getForEntity("/api/v1/users/me/sessions", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- Test 3: within the grace it's a concurrent tab, not a thief -----------------------------------------------

    @Test
    void reusingARotatedToken_withinTheGrace_isSuperseded_notTheft() {
        LoginTokens login = TestLogins.login(restTemplate, USER);
        long sessionId = TestLogins.sessionIdOf(login.accessToken());

        // The token is now 30 s OLD, but it's about to be consumed NOW: the grace must be measured from the
        // consumption, not from the token's creation (with created_at, the second call below would look like theft)
        clock.advance(Duration.ofSeconds(30));
        ResponseEntity<Map<String, Object>> winner = refresh(login.refreshToken());
        assertThat(winner.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> loser = refresh(login.refreshToken());

        assertThat(loser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(loser.getBody()).containsEntry("code", "INVALID_REFRESH_TOKEN");
        // No Set-Cookie at all: clearing it would delete the cookie the winner just set in the same browser
        assertThat(TestLogins.refreshSetCookie(loser)).isEmpty();
        assertThat(revokeReason(sessionId)).isNull();
        assertThat(eventCount("REFRESH_TOKEN_REUSED")).isZero();

        // And the winner's successor still works
        assertThat(refresh(TestLogins.refreshTokenIn(winner).orElseThrow()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- Test 4: twenty tabs wake up at once ------------------------------------------------------------------------

    @Test
    void twentyParallelRefreshes_withOneCookie_haveExactlyOneWinner_andNoServerError() throws Exception {
        LoginTokens login = TestLogins.login(restTemplate, USER);
        long sessionId = TestLogins.sessionIdOf(login.accessToken());

        List<ResponseEntity<Map<String, Object>>> responses = Concurrently.run(20, () -> refresh(login.refreshToken()));

        List<Integer> statuses = responses.stream().map(r -> r.getStatusCode().value()).toList();
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 401).hasSize(19);   // so no 409/500 either

        // All nineteen losers were superseded (within the grace), not treated as theft
        assertThat(revokeReason(sessionId)).isNull();
        assertThat(eventCount("REFRESH_TOKEN_REUSED")).isZero();
        assertThat(unconsumedTokenCount(sessionId)).isEqualTo(1);   // exactly one live token: the winner's successor

        String winnersCookie = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.OK)
                .findFirst().flatMap(TestLogins::refreshTokenIn).orElseThrow();
        assertThat(refresh(winnersCookie).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- Test 5: a refresh on a dead session must leave nothing behind (D13) ----------------------------------------

    @Test
    void refreshOnARevokedSession_leavesTheTokenUnconsumed_soARetryNeverLooksLikeTheft() {
        LoginTokens login = TestLogins.login(restTemplate, USER);
        long sessionId = TestLogins.sessionIdOf(login.accessToken());
        jdbcTemplate.update("update user_sessions set revoked_at = now(), revoke_reason = 'LOGOUT' where id = ?", sessionId);

        assertThat(refresh(login.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(consumedAtOfTheOnlyToken(sessionId)).as("the consume was rolled back").isNull();

        // The phone app retries later (lost response, a cookie store that ignored the clearing). If the first attempt
        // had consumed the token, this would be "consumed more than 10 s ago" = theft.
        clock.advance(PAST_THE_GRACE);
        assertThat(refresh(login.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(consumedAtOfTheOnlyToken(sessionId)).isNull();
        assertThat(eventCount("REFRESH_TOKEN_REUSED")).isZero();
        assertThat(revokeReason(sessionId)).as("the first reason is kept").isEqualTo("LOGOUT");
    }

    private ResponseEntity<Map<String, Object>> refresh(String rawRefreshToken) {
        return restTemplate.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(TestLogins.cookieHeader(rawRefreshToken)), JSON_OBJECT);
    }

    private String revokeReason(long sessionId) {
        return jdbcTemplate.queryForObject("select revoke_reason from user_sessions where id = ?", String.class, sessionId);
    }

    private int eventCount(String eventType) {
        return jdbcTemplate.queryForObject("select count(*) from security_events where user_account_id = ? and event_type = ?",
                Integer.class, userId, eventType);
    }

    private int unconsumedTokenCount(long sessionId) {
        return jdbcTemplate.queryForObject("select count(*) from refresh_tokens where session_id = ? and consumed_at is null",
                Integer.class, sessionId);
    }

    private Object consumedAtOfTheOnlyToken(long sessionId) {
        return jdbcTemplate.queryForObject("select consumed_at from refresh_tokens where session_id = ?", Object.class, sessionId);
    }
}
