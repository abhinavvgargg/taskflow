package com.abhinav.taskflow.organization;

import com.abhinav.taskflow.Concurrently;
import com.abhinav.taskflow.TestcontainersConfiguration;
import com.abhinav.taskflow.user.TestLogins;
import com.abhinav.taskflow.user.TestUsers;
import com.abhinav.taskflow.user.UserAccount;
import com.abhinav.taskflow.user.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.2 over HTTP, against the real database: D6's matrix for an owner, an admin and a member, then the flows.
 *
 * <p>Acme: alice OWNER · adam, ada ADMIN · mia, max MEMBER. Globex: bob OWNER, gil MEMBER (members of ANOTHER
 * organization: gil matters most, because only organization_id, not the role, keeps a plain member out).
 * Memberships other than the owners' are inserted with SQL: §3.3 (invitations) is the API that will add them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MembershipManagementIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {};
    private static final List<String> USERS = List.of("alice", "adam", "ada", "mia", "max", "bob", "gil");

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

    private final Map<String, Long> ids = new HashMap<>();
    private final Map<String, TestRestTemplate> clients = new HashMap<>();
    private long acme;

    @BeforeEach
    void seed() {
        TestUsers testUsers = new TestUsers(userAccountRepository, passwordEncoder, jdbcTemplate);
        testUsers.deleteAll();
        ids.clear();
        clients.clear();
        for (String name : USERS) {
            UserAccount user = testUsers.createVerifiedUser(name);
            ids.put(name, user.getId());
        }
        acme = createOrganization("alice", "acme");
        long globex = createOrganization("bob", "globex");
        addMember(acme, "adam", "ADMIN");
        addMember(acme, "ada", "ADMIN");
        addMember(acme, "mia", "MEMBER");
        addMember(acme, "max", "MEMBER");
        addMember(globex, "gil", "MEMBER");
    }

    // D6's matrix, the rows §3.2 owns. Each row runs against fresh data (@BeforeEach).
    @ParameterizedTest(name = "{0}: {1} {2} {3} -> {4} {5}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            # caller | action   | target | role   | status | code
            alice    | CHANGE   | mia    | ADMIN  | 200    | -
            alice    | CHANGE   | adam   | MEMBER | 200    | -
            alice    | CHANGE   | alice  | ADMIN  | 403    | CANNOT_MANAGE_MEMBER
            adam     | CHANGE   | mia    | ADMIN  | 403    | CANNOT_MANAGE_MEMBER
            adam     | CHANGE   | mia    | MEMBER | 200    | -
            adam     | CHANGE   | ada    | MEMBER | 403    | CANNOT_MANAGE_MEMBER
            mia      | CHANGE   | max    | ADMIN  | 403    | CANNOT_MANAGE_MEMBER
            mia      | CHANGE   | max    | MEMBER | 403    | CANNOT_MANAGE_MEMBER
            alice    | REMOVE   | adam   | -      | 204    | -
            alice    | REMOVE   | mia    | -      | 204    | -
            alice    | REMOVE   | alice  | -      | 403    | CANNOT_MANAGE_MEMBER
            adam     | REMOVE   | mia    | -      | 204    | -
            adam     | REMOVE   | ada    | -      | 403    | CANNOT_MANAGE_MEMBER
            adam     | REMOVE   | adam   | -      | 403    | CANNOT_MANAGE_MEMBER
            adam     | REMOVE   | alice  | -      | 403    | CANNOT_MANAGE_MEMBER
            mia      | REMOVE   | max    | -      | 403    | CANNOT_MANAGE_MEMBER
            alice    | LEAVE    | -      | -      | 409    | OWNER_CANNOT_LEAVE
            adam     | LEAVE    | -      | -      | 204    | -
            mia      | LEAVE    | -      | -      | 204    | -
            alice    | TRANSFER | mia    | -      | 204    | -
            adam     | TRANSFER | mia    | -      | 403    | ORGANIZATION_ROLE_REQUIRED
            mia      | TRANSFER | max    | -      | 403    | ORGANIZATION_ROLE_REQUIRED
            """)
    void theRoleMatrix(String caller, String action, String target, String role, int status, String code) {
        ResponseEntity<Map<String, Object>> response = switch (action) {
            case "CHANGE" -> changeRole(caller, target, role);
            case "REMOVE" -> remove(caller, target);
            case "LEAVE" -> leave(caller);
            case "TRANSFER" -> transfer(caller, target, TestUsers.PASSWORD);
            default -> throw new IllegalArgumentException(action);
        };

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        if (code != null) {
            assertThat(response.getBody()).containsEntry("code", code);
        }
    }

    @Test
    void changeRole_returnsTheStoredRow_andRecordsWhoChangedIt() {
        ResponseEntity<Map<String, Object>> response = changeRole("alice", "mia", "ADMIN");

        assertThat(response.getBody()).containsEntry("username", "mia").containsEntry("role", "ADMIN").doesNotContainKey("email");
        assertThat(roleOf("mia")).isEqualTo("ADMIN");
        // A bulk UPDATE bypasses JPA auditing: the statement must set these itself
        assertThat(updatedByOf("mia")).isEqualTo("alice");
    }

    @Test
    void changeRole_toTheCurrentRole_isA200ThatWritesNothing() {
        assertThat(changeRole("adam", "mia", "MEMBER").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(updatedByOf("mia")).isEqualTo("seed");   // still the inserted value: no write happened
    }

    @Test
    void aRemovedMember_getsA404_onTheirNextRequest() {
        assertThat(get("mia", "/api/v1/organizations/" + acme).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(remove("alice", "mia").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map<String, Object>> next = get("mia", "/api/v1/organizations/" + acme);
        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(next.getBody()).containsEntry("code", "ORGANIZATION_NOT_FOUND");
    }

    @Test
    void leavingViaMembersMe_removesOnlyTheCaller() {
        assertThat(leave("mia").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(roleOf("mia")).isNull();
        assertThat(roleOf("max")).isEqualTo("MEMBER");
    }

    @ParameterizedTest(name = "{0} of another organization")
    @CsvSource({"bob", "gil"})
    void aMemberOfAnotherOrganization_isNotAMemberHere(String outsider) {
        for (ResponseEntity<Map<String, Object>> response : List.of(
                changeRole("alice", outsider, "MEMBER"),
                remove("alice", outsider),
                transfer("alice", outsider, TestUsers.PASSWORD))) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("code", "MEMBER_NOT_FOUND");
        }
        // The failed transfer demoted alice in step 1, then rolled back: she is still the owner
        assertThat(roleOf("alice")).isEqualTo("OWNER");
        assertThat(ownerCount()).isEqualTo(1);
    }

    @Test
    void transfer_swapsTheRoles_andTheOldOwnerCantTransferAgain() {
        assertThat(transfer("alice", "mia", TestUsers.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(roleOf("mia")).isEqualTo("OWNER");
        assertThat(roleOf("alice")).isEqualTo("ADMIN");
        assertThat(updatedByOf("mia")).isEqualTo("alice");
        assertThat(updatedByOf("alice")).isEqualTo("alice");

        // The gate reads the role per request: alice's very next request is an admin's
        ResponseEntity<Map<String, Object>> again = transfer("alice", "max", TestUsers.PASSWORD);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(again.getBody()).containsEntry("code", "ORGANIZATION_ROLE_REQUIRED");
    }

    @Test
    void transfer_withAWrongPassword_is400_changesNothing_andCountsTowardLockout() {
        ResponseEntity<Map<String, Object>> response = transfer("alice", "mia", "not-the-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "CURRENT_PASSWORD_INCORRECT").containsEntry("field", "currentPassword");
        assertThat(roleOf("alice")).isEqualTo("OWNER");
        assertThat(roleOf("mia")).isEqualTo("MEMBER");
        assertThat(jdbcTemplate.queryForObject("select failed_login_attempts from user_accounts where id = ?",
                Integer.class, ids.get("alice"))).isEqualTo(1);
    }

    @Test
    void transfer_toYourself_is400_onTheUserIdField() {
        ResponseEntity<Map<String, Object>> response = transfer("alice", "alice", TestUsers.PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "CANNOT_TRANSFER_TO_SELF").containsEntry("field", "userId");
        assertThat(roleOf("alice")).isEqualTo("OWNER");
    }

    /**
     * Two transfers by the owner, to two different members, at the same instant. Step 1's "AND role = 'OWNER'" decides:
     * the second transaction waits for the first's row lock, then finds alice is no longer the owner (0 rows → 409).
     * If the first one commits before the second request even reaches the gate, the second is a 403 instead (the gate
     * already sees an admin): both are a correct refusal. Never two 204s, never two owners, never a 500.
     */
    @Test
    void twoTransfersAtOnce_exactlyOneWins_andThereIsExactlyOneOwner() throws Exception {
        TestRestTemplate asAlice = client("alice");
        List<Long> targets = List.of(ids.get("mia"), ids.get("max"));
        AtomicInteger next = new AtomicInteger();

        List<Integer> statuses = Concurrently.run(2, () -> asAlice.exchange(
                "/api/v1/organizations/" + acme + "/ownership-transfer", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", targets.get(next.getAndIncrement()), "currentPassword", TestUsers.PASSWORD)),
                JSON_OBJECT).getStatusCode().value());

        assertThat(statuses).containsOnlyOnce(204);
        assertThat(statuses).filteredOn(s -> s != 204).singleElement().isIn(409, 403);
        assertThat(ownerCount()).isEqualTo(1);
        assertThat(roleOf("alice")).isEqualTo("ADMIN");
    }

    // ---------------------------------------------------------------------------------------------------------------

    private long createOrganization(String owner, String slug) {
        ResponseEntity<Map<String, Object>> response = client(owner).exchange("/api/v1/organizations", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", slug, "slug", slug)), JSON_OBJECT);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) response.getBody().get("id")).longValue();
    }

    private void addMember(long organizationId, String name, String role) {
        jdbcTemplate.update("""
                insert into organization_memberships (id, organization_id, user_account_id, role, created_at, updated_at, created_by, updated_by)
                values (nextval('global_id_seq'), ?, ?, ?, now(), now(), 'seed', 'seed')
                """, organizationId, ids.get(name), role);
    }

    private TestRestTemplate client(String name) {
        return clients.computeIfAbsent(name, n -> TestLogins.as(restTemplateBuilder, restTemplate, n));
    }

    private String members() {
        return "/api/v1/organizations/" + acme + "/members/";
    }

    private ResponseEntity<Map<String, Object>> changeRole(String caller, String target, String role) {
        return client(caller).exchange(members() + ids.get(target), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("role", role)), JSON_OBJECT);
    }

    private ResponseEntity<Map<String, Object>> remove(String caller, String target) {
        return client(caller).exchange(members() + ids.get(target), HttpMethod.DELETE, null, JSON_OBJECT);
    }

    private ResponseEntity<Map<String, Object>> leave(String caller) {
        return client(caller).exchange(members() + "me", HttpMethod.DELETE, null, JSON_OBJECT);
    }

    private ResponseEntity<Map<String, Object>> transfer(String caller, String target, String password) {
        return client(caller).exchange("/api/v1/organizations/" + acme + "/ownership-transfer", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", ids.get(target), "currentPassword", password)), JSON_OBJECT);
    }

    private ResponseEntity<Map<String, Object>> get(String caller, String path) {
        return client(caller).exchange(path, HttpMethod.GET, null, JSON_OBJECT);
    }

    private String roleOf(String name) {
        List<String> roles = jdbcTemplate.queryForList(
                "select role from organization_memberships where organization_id = ? and user_account_id = ?",
                String.class, acme, ids.get(name));
        return roles.isEmpty() ? null : roles.getFirst();
    }

    private String updatedByOf(String name) {
        return jdbcTemplate.queryForObject(
                "select updated_by from organization_memberships where organization_id = ? and user_account_id = ?",
                String.class, acme, ids.get(name));
    }

    private int ownerCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from organization_memberships where organization_id = ? and role = 'OWNER'",
                Integer.class, acme);
    }
}
