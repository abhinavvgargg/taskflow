package com.abhinav.taskflow.common.security;

import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.common.logging.CorrelationIdFilter;
import com.abhinav.taskflow.user.TestLogins;
import com.abhinav.taskflow.user.TestUsers;
import com.abhinav.taskflow.user.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security rules a slice can't prove: actuator access, real credential checks against the database,
 * and behaviour that only exists on a real server (the ERROR dispatch to /error, headers on rejected requests).
 *
 * <p>Configuration deliberately identical to OrganizationApiIntegrationTest, so the context cache
 * reuses one application context and one Postgres container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TaskflowSecurityIntegrationTest {

    private static final String USER = "sec-user";
    private static final String ADMIN = "sec-admin";
    private static final String UNVERIFIED = "sec-unverified";
    private static final String LOCKED = "sec-locked";

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
    RestTemplateBuilder restTemplateBuilder;

    @BeforeEach
    void createUsers() {
        TestUsers testUsers = new TestUsers(userAccountRepository, passwordEncoder, jdbcTemplate);
        testUsers.deleteAll();
        testUsers.createVerifiedUser(USER);
        testUsers.createVerifiedAdmin(ADMIN);
        testUsers.createUnverifiedUser(UNVERIFIED);
        testUsers.createVerifiedUser(LOCKED);
        testUsers.lockUntil(LOCKED, Instant.now().plus(Duration.ofMinutes(10)));
    }

    /** Logs one of the users above in through /auth/login and returns a client that sends its bearer token. */
    private TestRestTemplate as(String username) {
        return TestLogins.as(restTemplateBuilder, restTemplate, username);
    }

    private ResponseEntity<String> login(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", Map.of("email", email, "password", password), String.class);
    }

    // --- Actuator -------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info"})
    void probeEndpoints_areOpenToAnonymousCallers(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void health_hidesDetails_fromAnonymousCallers() {
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange("/actuator/health", HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getBody()).containsEntry("status", "UP").doesNotContainKey("components");
    }

    @Test
    void health_hidesDetails_fromAuthenticatedNonAdmins() {
        ResponseEntity<Map<String, Object>> response = as(USER)
                .exchange("/actuator/health", HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP").doesNotContainKey("components");
    }

    @Test
    void health_showsDetails_toAdmins() {
        ResponseEntity<Map<String, Object>> response = as(ADMIN)
                .exchange("/actuator/health", HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("components");
    }

    @Test
    void metrics_rejectsAnonymousCallers_with401() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/metrics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void metrics_rejectsNonAdmins_with403() {
        ResponseEntity<String> response = as(USER).getForEntity("/actuator/metrics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void metrics_allowsAdmins() {
        // Together with the 403 above, this proves the principal's authorities carry the ROLE_ prefix:
        // without it, hasRole("ADMIN") would refuse a real admin.
        ResponseEntity<String> response = as(ADMIN).getForEntity("/actuator/metrics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- Bearer tokens ---------------------------------------------------------------------------------

    @Test
    void bearerToken_fromLogin_isLetThrough() {
        ResponseEntity<String> response = as(USER).getForEntity("/api/v1/organizations", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void basicCredentials_areNoLongerAccepted() {
        // Basic was removed in §2.2: a correct email and password in an Authorization header is just "no bearer token"
        ResponseEntity<Map<String, Object>> response = restTemplate.withBasicAuth(TestUsers.emailOf(USER), TestUsers.PASSWORD)
                .exchange("/api/v1/organizations", HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "AUTHENTICATION_REQUIRED");
    }

    @Test
    void malformedBearerToken_isRejectedWith401_invalidAccessToken() {
        TestRestTemplate client = TestLogins.bearerClient(restTemplateBuilder, restTemplate, "not-a-jwt");

        ResponseEntity<Map<String, Object>> response = client.exchange("/api/v1/organizations", HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "INVALID_ACCESS_TOKEN").containsKey("correlationId");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer error=\"invalid_token\"");
    }

    @Test
    void invalidBearerToken_isIgnoredOnAuthEndpoints() {
        // The resolver sees no token under /api/v1/auth/: login is decided by the password alone
        TestRestTemplate client = TestLogins.bearerClient(restTemplateBuilder, restTemplate, "not-a-jwt");

        ResponseEntity<String> response = client.postForEntity("/api/v1/auth/login",
                Map.of("email", TestUsers.emailOf(USER), "password", TestUsers.PASSWORD), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- Real credentials through /auth/login → TaskflowUserDetailsService ------------------------------

    @Test
    void login_emailIsCaseInsensitive() {
        // The user is stored as sec-user@example.com; the service normalises what the client typed.
        ResponseEntity<String> response = login("SEC-User@Example.COM", TestUsers.PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void login_withWrongPassword_isRejectedWith401() {
        ResponseEntity<String> response = login(TestUsers.emailOf(USER), "wrong-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_withUnknownEmail_isRejectedWith401() {
        ResponseEntity<String> response = login("nobody@example.com", TestUsers.PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_unverifiedUser_isRejectedWith403_withCorrectPassword() {
        ResponseEntity<String> response = login(TestUsers.emailOf(UNVERIFIED), TestUsers.PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void login_lockedUser_isRejectedWith401_evenWithCorrectPassword() {
        ResponseEntity<String> response = login(TestUsers.emailOf(LOCKED), TestUsers.PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- Behaviour of a rejected request on a real server ---------------------------------------------

    @Test
    void rejectedRequest_hasProblemDetailBody() {
        // Written by ProblemAuthenticationEntryPoint straight to the response: no /error dispatch involved any more.
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange("/api/v1/organizations", HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .containsEntry("status", 401)
                .containsEntry("code", "AUTHENTICATION_REQUIRED")
                .containsEntry("instance", "/api/v1/organizations")
                .containsKey("correlationId");
    }

    @Test
    void rejectedRequest_stillCarriesCorrelationId_andBearerChallenge() {
        // The correlation filter runs before the security chain, so even rejected requests are traceable.
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/organizations", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER_NAME)).isNotBlank();
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    void forbiddenRequest_hasProblemDetailBody() {
        ResponseEntity<Map<String, Object>> response = as(USER).exchange("/actuator/metrics", HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "ACCESS_DENIED").containsKey("correlationId");
    }

    @Test
    void authenticatedRequest_createsNoSession() {
        ResponseEntity<String> response = as(USER).getForEntity("/api/v1/organizations", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE)).isFalse();
    }
}
