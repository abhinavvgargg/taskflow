package com.abhinav.taskflow.user;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logs a {@link TestUsers} user in through the real {@code POST /api/v1/auth/login} and returns a client that sends
 * the access token on every request, so integration tests authenticate exactly like a real client.
 *
 * <p>A plain class with static methods, not a bean, for the same reason as TestUsers: a test-only bean would change
 * the context configuration and start a second application context and Postgres container.
 */
public final class TestLogins {

    private TestLogins() {}

    /** The access token from a successful login of {@code username} with {@link TestUsers#PASSWORD}. */
    public static String accessTokenFor(TestRestTemplate restTemplate, String username) {
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/login",
                Map.of("email", TestUsers.emailOf(username), "password", TestUsers.PASSWORD), Map.class);

        assertThat(response.getStatusCode()).as("login of %s", username).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("accessToken");
    }

    /**
     * A client for the same server that sends {@code Authorization: Bearer <accessToken>} on every request.
     * Built from Boot's RestTemplateBuilder, so it uses the application's ObjectMapper like the injected client.
     */
    public static TestRestTemplate bearerClient(RestTemplateBuilder builder, TestRestTemplate restTemplate, String accessToken) {
        return new TestRestTemplate(builder
                .rootUri(restTemplate.getRootUri())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    /** Logs in and returns a bearer client in one step. */
    public static TestRestTemplate as(RestTemplateBuilder builder, TestRestTemplate restTemplate, String username) {
        return bearerClient(builder, restTemplate, accessTokenFor(restTemplate, username));
    }
}
