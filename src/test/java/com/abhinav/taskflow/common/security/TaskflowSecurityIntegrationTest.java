package com.abhinav.taskflow.common.security;

import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.common.logging.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security rules a slice can't prove: actuator access, real credential checks, and behaviour that
 * only exists on a real server (the ERROR dispatch to /error, response headers on rejected requests).
 *
 * <p>Configuration deliberately identical to OrganizationApiIntegrationTest, so the context cache
 * reuses one application context and one Postgres container.
 *
 * <p>Pending §1.2 (needs a real ADMIN user): ADMIN reads /actuator/metrics → 200, and ADMIN sees
 * health {@code components}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TaskflowSecurityIntegrationTest {

    // Must match spring.security.user in src/test/resources/application-test.yml (role USER).
    private static final String USERNAME = "test-user";
    private static final String PASSWORD = "test-password";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {};

    @Autowired
    TestRestTemplate restTemplate;

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
        ResponseEntity<Map<String, Object>> response = restTemplate.withBasicAuth(USERNAME, PASSWORD)
                .exchange("/actuator/health", HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP").doesNotContainKey("components");
    }

    @Test
    void metrics_rejectsAnonymousCallers_with401() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/metrics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void metrics_rejectsNonAdmins_with403() {
        ResponseEntity<String> response = restTemplate.withBasicAuth(USERNAME, PASSWORD)
                .getForEntity("/actuator/metrics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- Real credentials through BasicAuthenticationFilter -------------------------------------------

    @Test
    void basicAuth_withCorrectPassword_isLetThrough() {
        ResponseEntity<String> response = restTemplate.withBasicAuth(USERNAME, PASSWORD)
                .getForEntity("/api/v1/organizations", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void basicAuth_withWrongPassword_isRejectedWith401() {
        ResponseEntity<String> response = restTemplate.withBasicAuth(USERNAME, "wrong-password")
                .getForEntity("/api/v1/organizations", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- Behaviour of a rejected request on a real server ---------------------------------------------

    @Test
    void rejectedRequest_hasJsonBody_becauseErrorPageIsPermitted() {
        // Basic's entry point calls sendError(401); Tomcat then dispatches to /error, which runs through
        // the security chain again. If /error were not permitted, that dispatch would be denied and the
        // body would be empty. (The body is Boot's error JSON, not our ProblemDetail — Phase 2.)
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange("/api/v1/organizations", HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("status", 401);
    }

    @Test
    void rejectedRequest_stillCarriesCorrelationId_andBasicChallenge() {
        // The correlation filter runs before the security chain, so even rejected requests are traceable.
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/organizations", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER_NAME)).isNotBlank();
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic");
    }

    @Test
    void authenticatedRequest_createsNoSession() {
        ResponseEntity<String> response = restTemplate.withBasicAuth(USERNAME, PASSWORD)
                .getForEntity("/api/v1/organizations", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE)).isFalse();
    }
}
