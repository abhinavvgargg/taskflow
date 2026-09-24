package com.abhinav.taskflow.common.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the URL access rules in {@link TaskflowSecurityConfig}, one row per rule.
 *
 * <p>The slice deliberately loads the security config and NO controllers ({@code @WebMvcTest}'s value is the
 * controller list, and it names none). So a request the chain lets through finds no handler and gets
 * <b>404</b>: in this class, 404 means "let through", while 401/403 mean "blocked".
 *
 * <p>Not tested here, because a slice gets them wrong (see docs/phase-1/SECURITY_TESTING_GUIDE.md §2):
 * actuator rules ({@code EndpointRequest} matches nothing without actuator loaded) and real credentials
 * (the slice runs as {@code dev}). Both are in {@link TaskflowSecurityIntegrationTest}.
 */
@WebMvcTest(TaskflowSecurityConfig.class)
@Import(TaskflowSecurityConfig.class)
class TaskflowSecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    enum Caller {
        ANONYMOUS(anonymous()),
        USER(user("user")),
        ADMIN(user("admin").roles("ADMIN"));

        private final RequestPostProcessor postProcessor;

        Caller(RequestPostProcessor postProcessor) {
            this.postProcessor = postProcessor;
        }
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource(delimiter = '|', textBlock = """
            # --- Auth endpoints: open to everyone, POST only, exact paths only -----------------------
            ANONYMOUS | POST   | /api/v1/auth/register                | 404
            ANONYMOUS | POST   | /api/v1/auth/login                   | 404
            ANONYMOUS | POST   | /api/v1/auth/verify-email            | 404
            ANONYMOUS | POST   | /api/v1/auth/verify-email/resend     | 404
            ANONYMOUS | POST   | /api/v1/auth/password-reset/request  | 404
            ANONYMOUS | POST   | /api/v1/auth/password-reset/confirm  | 404
            USER      | POST   | /api/v1/auth/login                   | 404
            ANONYMOUS | GET    | /api/v1/auth/register                | 401
            ANONYMOUS | DELETE | /api/v1/auth/login                   | 401
            ANONYMOUS | POST   | /api/v1/auth/other                   | 401

            # --- The API: any authenticated user --------------------------------------------------------
            ANONYMOUS | GET    | /api/v1/organizations                | 401
            ANONYMOUS | POST   | /api/v1/organizations                | 401
            USER      | GET    | /api/v1/organizations                | 404
            USER      | POST   | /api/v1/organizations                | 404
            ADMIN     | GET    | /api/v1/organizations                | 404

            # --- Everything undeclared: denied, even to admins -------------------------------------------
            ANONYMOUS | GET    | /nope                                | 401
            USER      | GET    | /nope                                | 403
            ADMIN     | GET    | /nope                                | 403
            ADMIN     | GET    | /logout                              | 403
            """)
    void accessRule(Caller caller, HttpMethod method, String path, int expectedStatus) throws Exception {
        mockMvc.perform(request(method, path).with(caller.postProcessor))
                .andExpect(status().is(expectedStatus));
    }
}
