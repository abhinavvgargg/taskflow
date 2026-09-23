# Testing in Spring Boot — Guide for §0.10

> Every example below was **run and passed** on this project's exact stack before being written here:
> Boot 3.5.16 · JDK 21 · Docker 29.8 · Testcontainers 1.21.4 · JUnit 5.12 · Mockito 5.17 · AssertJ 3.27.
> Full suite: 5 tests, green, ~9s wall-clock.

---

## 1. What you already have

`spring-boot-starter-test` is already in your `pom.xml`. It brings:

| Library | What it's for |
|---|---|
| **JUnit 5 (Jupiter)** | The test runner. `@Test`, `@BeforeEach`, `@Nested`, `@ParameterizedTest`. |
| **AssertJ** | Fluent assertions — `assertThat(x).isEqualTo(y)`. Prefer it over JUnit's `assertEquals`: better failure messages, better collection and exception support. |
| **Mockito** | Fakes for collaborators — `@Mock`, `given(...).willReturn(...)`, `verify(...)`. |
| **Spring Test / MockMvc** | Drive controllers through the real Spring MVC stack without starting a server. |
| **JSONPath, Hamcrest** | Assert on JSON response bodies: `jsonPath("$.slug").value("acme")`. |

What you **don't** have yet: Testcontainers. That's the one addition.

---

## 2. The four kinds of test

| Kind | Annotation | Loads | Real DB? | Speed | Catches |
|---|---|---|---|---|---|
| **Unit** | none (+ `@ExtendWith(MockitoExtension.class)`) | Nothing — plain Java | No | ~0.1s | Business rules in the service |
| **Web slice** | `@WebMvcTest` | Controllers, advice, filters, Jackson, validation | No | ~0.5s | HTTP contract: status codes, JSON shape, validation, error bodies |
| **JPA slice** | `@DataJpaTest` | Entities, repositories, Hibernate, Flyway | **Yes** | ~3s | Mappings, queries, constraints, auditing |
| **Integration** | `@SpringBootTest` | **Everything** | **Yes** | ~2s + startup | Wiring — does the whole thing work together |

💡 **Why slices exist.** `@SpringBootTest` starts the entire application. A slice starts only the layer under test and fakes the rest. Most bugs live in one layer, so test them there — fast, and when a slice test fails, you know which layer broke.

💡 **The pyramid.** Many unit tests, a fair number of slice tests, a few integration tests. Integration tests prove the pieces connect; they're the slowest to run and the hardest to diagnose when they fail.

---

## 3. Setup

### 3.1 Add Testcontainers to `pom.xml`

Beside the existing `spring-boot-starter-test`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

No versions — Boot's parent manages them (Testcontainers **1.21.4**, which includes the fix for Docker Engine 29's minimum-API change; older versions fail with *"client version 1.32 is too old"*).

**Docker must be running** when you run the database-backed tests.

### 3.2 One shared container definition

`src/test/java/com/abhinav/taskflow/TestcontainersConfiguration.java`:

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }
}
```

- **`@TestConfiguration`** — config that only exists in tests, and isn't picked up by component scanning.
- **The container is a Spring bean** — Spring starts it when the test context starts and stops it when the context closes. You never call `start()`.
- **`@ServiceConnection`** (Boot 3.1+) — reads the container's host, random port, username and password and wires them into the datasource automatically. It replaces the `@DynamicPropertySource` boilerplate in older tutorials.
- **Same image as `compose.yaml`** — tests run against the Postgres you actually ship.

Any test that needs a database adds `@Import(TestcontainersConfiguration.class)`.

---

## 4. The four examples

Each covers a **different** case from your §0.10 tasks — learn the pattern here, apply it there.

### 4.1 Unit test — `OrganizationServiceTest`

```java
@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    @Mock
    OrganizationRepository organizationRepository;

    @InjectMocks
    OrganizationService organizationService;

    @Test
    void findById_whenMissing_throwsNotFoundWithCode() {
        given(organizationRepository.findById(42L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> organizationService.findOrganizationById(42L))
                .isInstanceOfSatisfying(ResourceNotFoundException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(OrganizationErrorCode.ORGANIZATION_NOT_FOUND);
                    assertThat(ex.getProperties()).containsEntry("id", 42L);
                });
    }
}
```

- **No Spring at all.** `MockitoExtension` creates the mock; `@InjectMocks` builds the service and passes the mock into its constructor. Runs in ~0.1s.
- **Given / when / then.** `given(...)` arranges what the fake returns; the `assertThatThrownBy` lambda is the *when*; the assertions are the *then*.
- **`isInstanceOfSatisfying`** asserts the type *and* lets you inspect the exception — here the `ErrorCode` and structured properties you built in §0.7.
- **Name the test as a sentence**: `method_condition_expectedResult`. When it fails, the name tells you what broke.

### 4.2 Web slice — `OrganizationControllerTest`

```java
@WebMvcTest(OrganizationController.class)
class OrganizationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrganizationService organizationService;

    @MockitoBean
    PageableFactory pageableFactory;

    @Test
    void getById_returnsOrganizationJson() throws Exception {
        var dto = new OrganizationResponseDto(7L, "Acme Corp", "acme-corp", "system",
                Instant.parse("2026-09-01T10:00:00Z"));
        given(organizationService.findOrganizationById(7L)).willReturn(dto);

        mockMvc.perform(get("/api/v1/organizations/{id}", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.slug").value("acme-corp"))
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
```

- **`@WebMvcTest(OrganizationController.class)`** loads that controller plus `@RestControllerAdvice`, `Filter` beans, Jackson and validation. **Not** services, repositories, or `@Component`s.
- So every collaborator the controller needs must be provided — hence **two** `@MockitoBean`s. `PageableFactory` is a `@Component`, so the slice doesn't load it.
- **`MockMvc`** sends a request through the real `DispatcherServlet` — URL mapping, argument binding, `@Valid`, your `GlobalExceptionHandler`, Jackson serialisation — without opening a port.
- Your **`CorrelationIdFilter` runs here too.** Verified: the test log shows `GET /api/v1/organizations/7 -> 200`, and the header assertion passes.
- 📌 **Your §0.5 decision paying off:** `@EnableJpaAuditing` sits on its own `AuditingConfig`, which this slice doesn't load. Had it been on `TaskflowApplication`, this test would fail looking for JPA infrastructure that doesn't exist here.

⚠️ **`@MockitoBean`, not `@MockBean`.** `@MockBean` is **deprecated** since Boot 3.4 in favour of Spring Framework 6.2's `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`). Nearly every tutorial still shows `@MockBean`.

### 4.3 JPA slice — `OrganizationRepositoryTest`

```java
@DataJpaTest
@Import({TestcontainersConfiguration.class, AuditingConfig.class})
class OrganizationRepositoryTest {

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void existsBySlug_findsPersistedRow() {
        Organization org = new Organization();
        org.setName("Acme Corp");
        org.setSlug("acme-corp");
        entityManager.persistAndFlush(org);   // actually INSERT
        entityManager.clear();                // forget it, so reads hit the DB

        assertThat(organizationRepository.existsOrganizationBySlug("acme-corp")).isTrue();
        assertThat(organizationRepository.existsOrganizationBySlug("nope")).isFalse();
    }
}
```

- **Real Postgres** in a container, with **Flyway applying `V1`** first — so your migration is tested on every build. Verified in the log: `Migrating schema "public" to version "1 - create organizations"`.
- **Every test runs in a transaction that rolls back** afterwards. Tests don't see each other's data.
- **`persistAndFlush` + `clear` is the important part.** See trap §6.3.
- **`@Import(AuditingConfig.class)` is required.** See trap §6.1 — verified failure without it.

### 4.4 Integration — `OrganizationApiIntegrationTest`

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OrganizationApiIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    OrganizationRepository organizationRepository;

    @BeforeEach
    void cleanDatabase() {
        organizationRepository.deleteAll();
    }

    @Test
    void list_whenEmpty_returnsPageEnvelope() {
        ResponseEntity<PageResponse<OrganizationResponseDto>> response = restTemplate.exchange(
                "/api/v1/organizations?size=5", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).isEmpty();
        assertThat(response.getBody().size()).isEqualTo(5);
        assertThat(response.getBody().totalElements()).isZero();
    }
}
```

- **`RANDOM_PORT`** starts a real embedded Tomcat on a free port. `TestRestTemplate` is pre-configured to call it with relative URLs. This is a real HTTP round-trip.
- **`ParameterizedTypeReference`** — needed to deserialise a *generic* type like `PageResponse<OrganizationResponseDto>`. Java erases generics at runtime, so `PageResponse.class` alone can't tell Jackson what's inside `content`.
- **`@ActiveProfiles("test")`** — see trap §6.4.
- **`@BeforeEach` cleanup** — see trap §6.5.

---

## 5. Running tests

```bash
./mvnw test                                                   # everything
./mvnw test -Dtest=OrganizationServiceTest                    # one class
./mvnw test -Dtest=OrganizationServiceTest#findById_whenMissing_throwsNotFoundWithCode   # one method
./mvnw test -Dtest='Organization*Test'                        # a pattern
./mvnw clean test                                             # clean first — see below
```

**In IntelliJ:** the green ▶ in the gutter beside a class or method runs it. Right-click the `src/test/java` folder → *Run 'All Tests'*.

**Reading results:** Maven prints `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`. A **Failure** is an assertion that didn't hold; an **Error** is an exception nobody expected. Per-class reports land in `target/surefire-reports/`.

⚠️ **Run `clean` when the reports look odd.** `mvn test` doesn't delete old reports, so a deleted test class can still appear in `target/surefire-reports/` with its last result. It happened during verification.

⚠️ **Naming decides whether a test runs.** Surefire (what `mvn test` uses) runs classes named `*Test`, `*Tests`, `Test*` or `*TestCase`. A class named `OrganizationApiIT` is **silently skipped** — the `*IT` suffix belongs to the Failsafe plugin, which you haven't configured.

**Expected warning on JDK 21 — harmless for now:**

```
Mockito is currently self-attaching to enable the inline-mock-maker.
This will no longer work in future releases of the JDK.
```

Mockito attaches a Java agent at runtime to mock final classes. JDK 21 warns; a future JDK will refuse. The fix is configuring Mockito as a `-javaagent` in Surefire's `argLine`. Not needed for Phase 0 — just know what the warning means rather than ignoring it.

---

## 6. Traps — verified, not theoretical

### 6.1 `@DataJpaTest` does not load your auditing config ⚠️

Without `@Import(AuditingConfig.class)`, the first insert fails:

```
null value in column "created_at" of relation "organizations" violates not-null constraint
```

`@DataJpaTest` doesn't component-scan your `@Configuration` classes, so `@EnableJpaAuditing` never runs and the audit fields stay null. The error points at the database; the cause is a missing import.

### 6.2 `@AutoConfigureTestDatabase(replace = NONE)` is no longer needed

Older guides say `@DataJpaTest` swaps your datasource for an embedded one. **Since Boot 3.4 it doesn't replace a datasource supplied by `@ServiceConnection`.** Verified: the repository test passes without the annotation. Harmless if present, unnecessary if not.

### 6.3 Without `flush` + `clear`, you're not testing the database

JPA writes lazily. `save()` puts the entity in the **persistence context** (first-level cache); the INSERT runs at flush. And `findById` checks the persistence context *before* the database.

So *save → findById* hands back **the same Java object you just saved**, with no SQL at all. The test passes whether or not the row would ever reach Postgres.

- **Reading back?** Flush, then `clear()` the persistence context, then read — or use `TestEntityManager.persistFlushFind()`.
- **Testing a constraint?** You must **flush**. Verified: two `save()` calls with the same slug and no flush throw **nothing** — the INSERT never reaches Postgres. An `assertThatThrownBy` test then *fails* with "expecting code to raise a throwable", and it looks as though the constraint doesn't exist. Use `saveAndFlush`.

### 6.4 Tests inherit the `dev` profile

`application.yml` sets `spring.profiles.active: dev`, so any test without `@ActiveProfiles` runs as **dev**. Verified: three of four contexts logged `The following 1 profile is active: "dev"`.

Right now that's only noise (SQL at DEBUG). It stops being harmless the moment `application-dev.yml` gains something tests shouldn't get. `@ActiveProfiles("test")` overrides it. Same §0.3 trade-off, reaching into your tests.

### 6.5 Integration test data is committed, not rolled back

`@DataJpaTest` rolls back after each test. **`@SpringBootTest` with `RANDOM_PORT` does not** — the HTTP request is handled on a server thread with its own transaction, which commits. Data from one test is still there for the next.

Clean up in `@BeforeEach`, or use unique values per test. Otherwise tests pass or fail depending on the order they ran in.

### 6.6 Timestamps lose precision on a round-trip

`Instant` holds nanoseconds; `timestamptz` holds microseconds. A value read back from the database can differ from the one you hold in memory by a few hundred nanoseconds. Compare with `isCloseTo(expected, within(1, ChronoUnit.MILLIS))` or truncate both — never exact equality.

### 6.7 Exception translation happens at the repository

Verified: a duplicate slug through `TestEntityManager.persistAndFlush` throws **`org.hibernate.exception.ConstraintViolationException`**. The same duplicate through `organizationRepository.saveAndFlush` throws Spring's **`DataIntegrityViolationException`**.

Spring converts vendor exceptions into its own `DataAccessException` hierarchy **at the repository proxy** (and at transaction commit). `TestEntityManager` isn't a repository, so nothing converts. It matters beyond tests: your §0.7 handler catches `DataIntegrityViolationException`, which only exists *because* the call went through a repository or a transaction.

---

## 7. Context caching — why there were 3 containers

The full run started **three** Postgres containers, not one. Spring caches an `ApplicationContext` per **unique configuration** and reuses it across test classes that match. Each distinct context gets its own `TestcontainersConfiguration` bean, and so its own container.

| Test class | Configuration | Context |
|---|---|---|
| `TaskflowApplicationTests` | `@SpringBootTest`, MOCK web env, profile `dev` | #1 |
| `OrganizationApiIntegrationTest` | `@SpringBootTest`, **RANDOM_PORT**, profile **`test`** | #2 |
| `OrganizationRepositoryTest` | `@DataJpaTest` | #3 |
| `OrganizationControllerTest` | `@WebMvcTest` (no container) | #4 |

`TaskflowApplicationTests` and the integration test are both `@SpringBootTest`, but a different web environment and a different active profile make them two different contexts — so two full application starts and two containers.

💡 **This is why Spring test suites get slow.** Every combination of profiles, `@MockitoBean`s, properties and imports is a new context. A suite with forty slightly-different configurations starts forty applications. Align configuration and they share.

📊 **Measured (2026-09-23):** `TaskflowApplicationTests` was given exactly the integration test's configuration. Containers dropped **3 → 2**, and `contextLoads` went from a full application start to **0.009s** — it reused the cached context. Full suite: **13 tests, 8s wall-clock**.

---

## 8. Your §0.10 tasks

**Done — written as worked examples (2026-09-23)**, in `organization`. From Phase 1 onward, tests are written by you.

📌 **Mutation-checked:** with the `@Max`-on-a-String bug put back in a scratch copy, `create_withValidBody_returns201WithLocation` fails in 0.4s with `Status expected:<201> but was:<400>`. A test only counts if it fails when the bug is present.

**1. Unit — `createOrganization`**
- A duplicate slug throws `ResourceConflictException` with `DUPLICATE_SLUG`, **and `save` is never called**. (`verify(repository, never()).save(any())`)
- A new slug calls `save` exactly once.

**2. Web slice — validation and the contract**
- `POST` with `{"name":"","slug":"BAD SLUG"}` → **400**, content type `application/problem+json`, `code` = `VALIDATION_FAILED`, `fieldErrors` naming **both** `name` and `slug`, a `correlationId` present.
- `POST` with `{"name":"Acme Corp","slug":"acme-corp"}` → **201** with a `Location` header. 📌 This is the regression test for the `@Max`-on-a-String bug — it would have caught it in seconds.

**3. JPA slice — the database really behaves**
- Auditing: after persisting, `createdAt`, `updatedAt`, `createdBy` and `updatedBy` are all populated, and `createdAt` equals `updatedAt` (mind §6.6).
- Constraint: saving a second organization with the same slug throws `DataIntegrityViolationException` (mind §6.3 — it must be flushed).

**4. Integration — end to end**
- `POST` → 201 → follow the `Location` header with a `GET` → 200 with the same slug.
- `POST` the same slug again → **409** with `code` = `DUPLICATE_SLUG`.

**5. Fix `TaskflowApplicationTests`**
It currently fails — it starts the whole app with no database. Either give it a container, or delete it now that the integration test proves the context loads. 🏗️ Your call; justify it.

**6. 📊 Measure**
Record the suite's wall-clock time and container count, then try reducing contexts as in §7.

---

## 9. Interview questions this section answers

- **Why not H2 in tests?** It lies about Postgres: different locking (`SELECT … FOR UPDATE`, needed in Phase 5), different constraint error codes (your §0.7 mapping), different index behaviour (Phase 8). Tests would pass against a database you don't ship.
- **Why is a Spring test suite slow, and what's context caching?** Each unique test configuration starts a whole application context; identical configurations share one. §7 is the worked example.
- **`@WebMvcTest` vs `@SpringBootTest`?** A slice loads one layer and fakes the rest — fast and precise. `@SpringBootTest` loads everything — proves wiring, slower, harder to diagnose.
- **`@MockBean` vs `@MockitoBean`?** `@MockBean` is deprecated since Boot 3.4; `@MockitoBean` is the Spring Framework 6.2 replacement.
- **Why can a repository test pass without touching the database?** The persistence context serves reads from memory until you flush and clear.
