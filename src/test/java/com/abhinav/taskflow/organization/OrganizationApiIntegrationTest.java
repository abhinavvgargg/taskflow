package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.common.web.PageResponse;
import com.abhinav.taskflow.user.TestUsers;
import com.abhinav.taskflow.user.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OrganizationApiIntegrationTest {

    private static final String USERNAME = "org-tester";

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private TestRestTemplate asUser;

    @BeforeEach
    void cleanDatabase() {
        organizationRepository.deleteAll();

        TestUsers testUsers = new TestUsers(userAccountRepository, passwordEncoder, jdbcTemplate);
        testUsers.deleteAll();
        testUsers.createVerifiedUser(USERNAME);
        asUser = restTemplate.withBasicAuth(TestUsers.emailOf(USERNAME), TestUsers.PASSWORD);
    }

    @Test
    void list_whenEmpty_returnsPageEnvelope() {
        ResponseEntity<PageResponse<OrganizationResponseDto>> response = asUser.exchange(
                "/api/v1/organizations?size=5", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).isEmpty();
        assertThat(response.getBody().size()).isEqualTo(5);
        assertThat(response.getBody().totalElements()).isZero();
    }

    @Test
    void create_thenFetchViaLocationHeader() {
        var request = new OrganizationRequestDto("Acme Corp", "acme-corp");

        ResponseEntity<OrganizationResponseDto> created =
                asUser.postForEntity("/api/v1/organizations", request, OrganizationResponseDto.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        URI location = created.getHeaders().getLocation();
        assertThat(location).isNotNull();

        ResponseEntity<OrganizationResponseDto> fetched =
                asUser.getForEntity(location, OrganizationResponseDto.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().id()).isEqualTo(created.getBody().id());
        assertThat(fetched.getBody().slug()).isEqualTo("acme-corp");
        // The auditor records the caller's app username: not "system", and never their email.
        assertThat(fetched.getBody().createdBy()).isEqualTo(USERNAME);
    }

    @Test
    void create_duplicateSlug_returns409ProblemDetail() {
        var request = new OrganizationRequestDto("Acme Corp", "acme-corp");
        asUser.postForEntity("/api/v1/organizations", request, Void.class);

        ResponseEntity<ProblemDetail> response =
                asUser.postForEntity("/api/v1/organizations", request, ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "DUPLICATE_SLUG")
                .containsEntry("slug", "acme-corp")
                .containsKey("correlationId");
    }
}
