package com.abhinav.taskflow.user.session;

import com.abhinav.taskflow.AdjustableClock;
import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.user.TestLogins;
import com.abhinav.taskflow.user.TestLogins.LoginTokens;
import com.abhinav.taskflow.user.TestUsers;
import com.abhinav.taskflow.user.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static com.abhinav.taskflow.common.error.ProblemBodies.withoutRequestSpecificFields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 §2.2 + §2.4: a session that has ended stops its access tokens on the NEXT request (decision D5), and one
 * user can't end another user's session by guessing its id (the owner is in the UPDATE's WHERE clause).
 *
 * <p>Configuration identical to every other integration test: one cached context, one Postgres container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SessionRevocationIntegrationTest {

    private static final String ALICE = "rev-alice";
    private static final String BOB = "rev-bob";
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
    AdjustableClock clock;

    @BeforeEach
    void setUp() {
        clock.reset();
        TestUsers testUsers = new TestUsers(userAccountRepository, passwordEncoder, jdbcTemplate);
        testUsers.deleteAll();   // sessions, refresh tokens and events cascade with their user
        testUsers.createVerifiedUser(ALICE);
        testUsers.createVerifiedUser(BOB);
    }

    // --- Test 1: revocation reaches the access token on the next request ------------------------------------------

    @Test
    void revokedSession_itsAccessToken_isRejectedOnTheNextRequest() {
        LoginTokens alice = TestLogins.login(restTemplate, ALICE);
        TestRestTemplate client = bearer(alice);
        assertThat(client.getForEntity(SESSIONS, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Revoked behind the API's back, as an admin would by hand: the token itself is unchanged and unexpired
        jdbcTemplate.update("update user_sessions set revoked_at = now(), revoke_reason = 'LOGOUT' where id = ?",
                TestLogins.sessionIdOf(alice.accessToken()));

        ResponseEntity<Map<String, Object>> response = client.exchange(SESSIONS, HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "INVALID_ACCESS_TOKEN");
        // "Refresh or log in again", in the standard header a client can act on
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer error=\"invalid_token\"");
    }

    // --- Test 9: the IDOR rule --------------------------------------------------------------------------------------

    @Test
    void anotherUsersSessionId_isA404_identicalToAnUnknownId_andTheirSessionSurvives() {
        LoginTokens alice = TestLogins.login(restTemplate, ALICE);
        LoginTokens bob = TestLogins.login(restTemplate, BOB);
        long bobsSessionId = TestLogins.sessionIdOf(bob.accessToken());
        long unknownSessionId = bobsSessionId + 1_000_000;

        ResponseEntity<Map<String, Object>> bobsId = bearer(alice)
                .exchange(SESSIONS + "/{id}", HttpMethod.DELETE, null, JSON_OBJECT, bobsSessionId);
        ResponseEntity<Map<String, Object>> unknownId = bearer(alice)
                .exchange(SESSIONS + "/{id}", HttpMethod.DELETE, null, JSON_OBJECT, unknownSessionId);

        assertThat(bobsId.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bobsId.getBody()).containsEntry("code", "SESSION_NOT_FOUND");
        // Indistinguishable: a 403, or any difference in the body, would tell alice that bob's id exists
        assertThat(withoutRequestSpecificFields(bobsId.getBody()))
                .isEqualTo(withoutRequestSpecificFields(unknownId.getBody()));

        // bob noticed nothing
        assertThat(bearer(bob).getForEntity(SESSIONS, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revokedAt(bobsSessionId)).isNull();
    }

    @Test
    void deleteWithATrailingSlash_doesNotReachLogOutEverywhere() {
        LoginTokens alice = TestLogins.login(restTemplate, ALICE);

        // Neither "/sessions" (all) nor "/sessions/{id}" (one): Spring Framework 6 doesn't match trailing slashes
        ResponseEntity<String> response = bearer(alice).exchange(SESSIONS + "/", HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bearer(alice).getForEntity(SESSIONS, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revokedAt(TestLogins.sessionIdOf(alice.accessToken()))).isNull();
    }

    private TestRestTemplate bearer(LoginTokens tokens) {
        return TestLogins.bearerClient(restTemplateBuilder, restTemplate, tokens.accessToken());
    }

    private Object revokedAt(long sessionId) {
        return jdbcTemplate.queryForObject("select revoked_at from user_sessions where id = ?", Object.class, sessionId);
    }
}
