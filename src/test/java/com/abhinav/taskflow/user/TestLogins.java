package com.abhinav.taskflow.user;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.text.ParseException;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logs a {@link TestUsers} user in through the real {@code POST /api/v1/auth/login} and returns what a real client
 * gets back: the access token (body) and the refresh token (cookie). Integration tests authenticate exactly like a
 * real client.
 *
 * <p>A plain class with static methods, not a bean, for the same reason as TestUsers: a test-only bean would change
 * the context configuration and start a second application context and Postgres container.
 *
 * <p>The test HTTP client (the JDK HttpClient Boot picks when no other is on the classpath) keeps no cookie jar, so
 * nothing is sent automatically: a test that wants to send the refresh cookie adds {@link #cookieHeader} itself, the
 * same way the Postman collection does with the jar disabled.
 */
public final class TestLogins {

    /** The refresh cookie's name, from application.yml (taskflow.security.sessions.cookie.name). */
    public static final String REFRESH_COOKIE = "taskflow_refresh";

    private TestLogins() {}

    /** One device's credentials after a login: the access token and the raw refresh token from the cookie. */
    public record LoginTokens(String accessToken, String refreshToken) {

        @Override
        public String toString() {
            return "LoginTokens{<redacted>}";
        }
    }

    /** Logs {@code username} in with {@link TestUsers#PASSWORD}; fails the test unless it's a 200 with both tokens. */
    public static LoginTokens login(TestRestTemplate restTemplate, String username) {
        return login(restTemplate, username, TestUsers.PASSWORD);
    }

    public static LoginTokens login(TestRestTemplate restTemplate, String username, String password) {
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity("/api/v1/auth/login",
                Map.of("email", TestUsers.emailOf(username), "password", password), LoginResponse.class);

        assertThat(response.getStatusCode()).as("login of %s", username).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        String refreshToken = refreshTokenIn(response)
                .orElseThrow(() -> new AssertionError("login of " + username + " set no refresh cookie"));
        return new LoginTokens(response.getBody().accessToken(), refreshToken);
    }

    /** The access token from a successful login of {@code username} with {@link TestUsers#PASSWORD}. */
    public static String accessTokenFor(TestRestTemplate restTemplate, String username) {
        return login(restTemplate, username).accessToken();
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

    /** Headers carrying exactly one refresh cookie, as a browser would send it to /api/v1/auth/*. */
    public static HttpHeaders cookieHeader(String rawRefreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + rawRefreshToken);
        return headers;
    }

    /** The whole {@code Set-Cookie} header for the refresh cookie, if the response has one. */
    public static Optional<String> refreshSetCookie(ResponseEntity<?> response) {
        return response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(REFRESH_COOKIE + "="))
                .findFirst();
    }

    /** The refresh token a response sets (the cookie's value), if any. A clearing cookie has an empty value. */
    public static Optional<String> refreshTokenIn(ResponseEntity<?> response) {
        return refreshSetCookie(response)
                .map(header -> header.substring((REFRESH_COOKIE + "=").length(), indexOfOrEnd(header, ';')));
    }

    /**
     * The session id inside an access token (the {@code sid} claim). Readable without the key: a JWT is signed, not
     * encrypted (Phase 2's first lesson). Lets a test find "this device's" session row without another request.
     */
    public static long sessionIdOf(String accessToken) {
        try {
            return Long.parseLong(SignedJWT.parse(accessToken).getJWTClaimsSet().getStringClaim("sid"));
        } catch (ParseException e) {
            throw new AssertionError("not a JWT", e);
        }
    }

    private static int indexOfOrEnd(String value, char c) {
        int index = value.indexOf(c);
        return index < 0 ? value.length() : index;
    }
}
