package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.common.error.ProblemBodies;
import com.abhinav.taskflow.common.tenant.OrganizationRole;
import com.abhinav.taskflow.common.web.PageResponse;
import com.abhinav.taskflow.user.TestLogins;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OrganizationApiIntegrationTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
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

    private UserAccount alice;
    private TestRestTemplate asAlice;
    private TestRestTemplate asBob;

    @BeforeEach
    void cleanDatabase() {
        TestUsers testUsers = new TestUsers(userAccountRepository, passwordEncoder, jdbcTemplate);
        testUsers.deleteAll();   // organizations first: an owner's account can't be deleted while it owns one
        alice = testUsers.createVerifiedUser(ALICE);
        testUsers.createVerifiedUser(BOB);
        asAlice = TestLogins.as(restTemplateBuilder, restTemplate, ALICE);
        asBob = TestLogins.as(restTemplateBuilder, restTemplate, BOB);
    }

    @Test
    void list_whenEmpty_returnsPageEnvelope() {
        ResponseEntity<PageResponse<OrganizationResponseDto>> response = myOrganizations(asAlice, "?size=5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).isEmpty();
        assertThat(response.getBody().size()).isEqualTo(5);
        assertThat(response.getBody().totalElements()).isZero();
    }

    @Test
    void create_makesTheCallerTheOwner_thenFetchViaLocationHeader() {
        ResponseEntity<OrganizationResponseDto> created = create(asAlice, "Acme Corp", "acme-corp");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().role()).isEqualTo(OrganizationRole.OWNER);
        URI location = created.getHeaders().getLocation();
        assertThat(location).isNotNull();

        // The membership row exists, committed with the organization
        assertThat(jdbcTemplate.queryForList(
                "select role from organization_memberships where organization_id = ? and user_account_id = ?",
                String.class, created.getBody().id(), alice.getId()))
                .containsExactly("OWNER");

        ResponseEntity<OrganizationResponseDto> fetched = asAlice.getForEntity(location, OrganizationResponseDto.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().id()).isEqualTo(created.getBody().id());
        assertThat(fetched.getBody().slug()).isEqualTo("acme-corp");
        assertThat(fetched.getBody().role()).isEqualTo(OrganizationRole.OWNER);
        // The auditor records the caller's app username: not "system", and never their email.
        assertThat(fetched.getBody().createdBy()).isEqualTo(ALICE);
    }

    @Test
    void create_duplicateSlug_returns409ProblemDetail() {
        create(asAlice, "Acme Corp", "acme-corp");

        ResponseEntity<ProblemDetail> response = asAlice.postForEntity("/api/v1/organizations",
                new OrganizationRequestDto("Acme Corp", "acme-corp"), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "DUPLICATE_SLUG")
                .containsEntry("slug", "acme-corp")
                .containsKey("correlationId");
    }

    @Test
    void myOrganizations_listsOnlyTheCallersOwn_sortedByName() {
        create(asAlice, "Zeta", "zeta");
        create(asAlice, "Acme", "acme");
        create(asBob, "Globex", "globex");

        ResponseEntity<PageResponse<OrganizationResponseDto>> alices = myOrganizations(asAlice, "");
        ResponseEntity<PageResponse<OrganizationResponseDto>> bobs = myOrganizations(asBob, "");

        assertThat(alices.getBody()).isNotNull();
        assertThat(alices.getBody().content()).extracting(OrganizationResponseDto::slug).containsExactly("acme", "zeta");
        assertThat(alices.getBody().content()).extracting(OrganizationResponseDto::role).containsOnly(OrganizationRole.OWNER);
        assertThat(alices.getBody().totalElements()).isEqualTo(2);
        assertThat(bobs.getBody()).isNotNull();
        assertThat(bobs.getBody().content()).extracting(OrganizationResponseDto::slug).containsExactly("globex");
    }

    @Test
    void aNonMember_getsTheSame404_asForAnOrganizationThatDoesNotExist() {
        long acme = create(asAlice, "Acme", "acme").getBody().id();

        for (String suffix : List.of("", "/members")) {
            ResponseEntity<Map<String, Object>> notMine =
                    asBob.exchange("/api/v1/organizations/" + acme + suffix, HttpMethod.GET, null, JSON_OBJECT);
            ResponseEntity<Map<String, Object>> nonExistent =
                    asBob.exchange("/api/v1/organizations/999999999" + suffix, HttpMethod.GET, null, JSON_OBJECT);

            assertThat(notMine.getStatusCode()).as(suffix).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(nonExistent.getStatusCode()).as(suffix).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(notMine.getBody()).as(suffix).containsEntry("code", "ORGANIZATION_NOT_FOUND");
            assertThat(ProblemBodies.withoutRequestSpecificFields(notMine.getBody())).as(suffix)
                    .isEqualTo(ProblemBodies.withoutRequestSpecificFields(nonExistent.getBody()));
        }
    }

    @Test
    void members_listsTheOrganizationsMembers_withoutEmails() {
        long acme = create(asAlice, "Acme", "acme").getBody().id();
        create(asBob, "Globex", "globex");   // bob is a member of ANOTHER organization: must not appear

        ResponseEntity<Map<String, Object>> response =
                asAlice.exchange("/api/v1/organizations/" + acme + "/members", HttpMethod.GET, null, JSON_OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) response.getBody().get("content");
        assertThat(members).hasSize(1);
        // JSON numbers come back as Integer when they fit: compare as a number, not as a Long
        assertThat(((Number) members.getFirst().get("userId")).longValue()).isEqualTo(alice.getId());
        assertThat(members.getFirst())
                .containsEntry("username", ALICE)
                .containsEntry("displayName", "Test " + ALICE)
                .containsEntry("role", "OWNER")
                .containsKey("joinedAt")
                .doesNotContainKey("email");
    }

    @Test
    void members_canBeSortedByJoinedAt_andRejectsUnknownSorts() {
        long acme = create(asAlice, "Acme", "acme").getBody().id();
        String members = "/api/v1/organizations/" + acme + "/members";

        assertThat(asAlice.exchange(members + "?sort=joinedAt,desc", HttpMethod.GET, null, JSON_OBJECT).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> badSort =
                asAlice.exchange(members + "?sort=email", HttpMethod.GET, null, JSON_OBJECT);
        assertThat(badSort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badSort.getBody()).containsEntry("code", "INVALID_SORT_PROPERTY");
    }

    // Through the real server and dispatcher: a plain OPTIONS (not a CORS preflight) on an org-scoped path
    @Test
    void options_onAnOrganization_isAnsweredWithAllow_notA500() {
        long acme = create(asAlice, "Acme", "acme").getBody().id();

        ResponseEntity<Void> response = asAlice.exchange("/api/v1/organizations/" + acme, HttpMethod.OPTIONS, null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.GET);
    }

    @Test
    void anOwnersAccount_cannotBeDeleted_whileItOwnsAnOrganization() {
        create(asAlice, "Acme", "acme");

        // Autocommit: the deferred trigger runs at the end of this statement's own transaction
        assertThatThrownBy(() -> jdbcTemplate.update("delete from user_accounts where id = ?", alice.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organizations_has_owner");
        assertThat(userAccountRepository.existsById(alice.getId())).isTrue();
    }

    private ResponseEntity<OrganizationResponseDto> create(TestRestTemplate client, String name, String slug) {
        ResponseEntity<OrganizationResponseDto> response = client.postForEntity("/api/v1/organizations",
                new OrganizationRequestDto(name, slug), OrganizationResponseDto.class);
        assertThat(response.getStatusCode()).as("create %s", slug).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private static ResponseEntity<PageResponse<OrganizationResponseDto>> myOrganizations(TestRestTemplate client, String query) {
        return client.exchange("/api/v1/organizations" + query, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
    }
}
