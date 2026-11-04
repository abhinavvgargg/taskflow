# Testing 1. Strategy, slices and context caching

> Which kind of test proves what, how to keep the suite fast, and the slice traps this project hit. The step-by-step guides stay in `phase-0/TESTING_GUIDE.md` and `phase-1/SECURITY_TESTING_GUIDE.md`; persistence specifics are [jpa 09](../jpa/09-testing-jpa.md). Sources: [../SOURCES.md](../SOURCES.md).

## The four kinds of test

| Kind | Annotation | Loads | Real DB | Catches |
|---|---|---|---|---|
| Unit | none (+ Mockito) | plain Java | no | business rules (`OrganizationRole` tables, `MembershipService`, `MembershipWorkflow` ordering) |
| Web slice | `@WebMvcTest` | controllers, advice, filters, interceptors, Jackson, validation | no | HTTP contract: status, JSON, validation, error bodies, routing |
| JPA slice | `@DataJpaTest` | entities, repositories, Hibernate, Flyway | yes | mappings, queries, constraints, auditing |
| Integration | `@SpringBootTest(RANDOM_PORT)` | everything | yes | wiring, security, real credentials, races |

**The pyramid:** many unit tests, a fair number of slices, a few integration tests. A slice failure tells you which layer broke. **Real PostgreSQL via Testcontainers, never H2** ([jpa 09](../jpa/09-testing-jpa.md)).

Measured: Phase 0 → 13 tests, 8 s; Phase 2 close → 83, 12 s; Phase 3 §3.0 → 101, 14 s; §3.1 → 136, 18.6 s; §3.2 → 223, 23.2 s. **Two Postgres containers throughout** (since Phase 0's fix).

## Context caching: why a suite gets slow

Spring caches an `ApplicationContext` per **unique test configuration** and reuses it across classes; each distinct context starts the application again (and, here, its own Postgres container). Every different combination of profiles, `@MockitoBean`s, properties and imports is a new context.

*Measured (Phase 0, 2026-09-23):* 3 containers, because `TaskflowApplicationTests` (MOCK web env, `dev`) and the integration test (RANDOM_PORT, `test`) differed. Given the same configuration: **3 → 2 containers**, and `contextLoads` dropped to 0.009 s.

**Rules:**
- Every integration class uses exactly `@SpringBootTest(webEnvironment = RANDOM_PORT) @Import(TestcontainersConfiguration.class) @ActiveProfiles("test")`; every JPA slice exactly `@DataJpaTest @Import({TestcontainersConfiguration.class, AuditingConfig.class})`.
- **Test-only beans go in the configuration everyone imports** (`AdjustableClock`, `CapturingEmailSender` in `TestcontainersConfiguration`), never a per-class `@TestConfiguration` or `@MockitoBean Clock` (each a new context and container).
- **Helpers are plain classes, not beans** (`TestUsers`, `TestLogins`): registering them would change the configuration.
- Check the container count after adding a test class.

## Profiles and configuration in tests

- **Tests without `@ActiveProfiles` inherit `dev`** from `application.yml` (*verified*): harmless noise until `dev` gains something tests shouldn't get. Integration tests say `@ActiveProfiles("test")`.
- **Test configuration lives in `src/test/resources`** (*what happened to us:* `application-test.yml` with an admin user was once in `src/main`, packaged into the production jar).
- **Declaring your own user service removes Boot's generated user,** so tests create real users with `TestUsers` (SQL for things with no API: promoting to admin, locking, memberships before invitations).

## Web slice traps

- **`@WebMvcTest`'s value is the controller list.** `@WebMvcTest(TaskflowSecurityConfig.class)` loads **no controllers**: in that security slice, **404 means "let through"** and 401/403 mean "blocked" (*verified*; documented on the class).
- **A slice can't test actuator rules** (`EndpointRequest` matches nothing without actuator: 401/403 in the slice, 200 in the app) **or real credentials** (it runs as `dev`). Those go in integration tests.
- **Every web slice loads `HandlerInterceptor` and `WebMvcConfigurer` beans** (*verified*, spring-boot-test-autoconfigure 3.5.16), so since §3.1 the tenant gate is in every slice: slices need `@MockitoBean MembershipLookup` and `@Import(TenantContext.class)`, or they don't start.
- **Beans the security config needs must be provided** (`@MockitoBean JwtDecoder`, `SessionStatus`, `TaskflowUserDetailsService`), and the config should ask for **interfaces** so mocks fit.
- **Authenticate as our principal type:** `@WithMockUser` puts Spring's `User` in the context, which `@AuthenticationPrincipal(errorOnInvalidType = true)` and the gate refuse (500, by design). Use `with(authentication(new TaskflowAuthenticationToken(new AuthenticatedUser(...))))` and stub `MembershipLookup.roleOf`. Spring Security Test's `jwt()` always builds a `JwtAuthenticationToken`, the wrong type too.
- **No class-level `@WithMockUser` in security tests:** it applies to the tests meant to be anonymous. Each request states its caller.
- **Test-only controllers** (the gate's probe) are nested classes registered with `@Import`: inner classes of test classes are excluded from component scanning.
- **The same mistake shows different statuses in different tests:** adding only the security starter gave 401/403 in slices and 401 or a **302** in the integration test (real Tomcat's second trip to `/error` with the default chain); MockMvc never makes that trip.

## Testing startup failures

`ApplicationContextRunner` starts only the configuration under test (`JwtProperties`, `JwtKeyConfig`, a clock) with the profile set by an initializer, in under a second, with no container (Phase 3 §3.0). A full `prod` run would fail first for another reason (no `EmailSender`), which is why the manual check never proved "prod refuses without a key". **An allow-list needs a test with a profile nobody listed** (`staging`): only that one tells it from a block-list.

## Manual runs

You run the app from IntelliJ and call the API with Postman (one collection per user). Postman's cookie jar can't present an old token: disable it and send `Cookie: name={{var}}` yourself; concurrency checks need a terminal (the runner is sequential). Report results row by row: §3.2's run was "everything works" with no rows recorded, so the automated suite is its only detailed proof.

## Interview questions

1. **Why not H2?** It lies about locking, error codes and indexes.
2. **Why is a Spring test suite slow, and how did yours stay at two containers?** Every distinct configuration is a new context; all tests of a kind share one configuration and test beans live in the shared import.
3. **What can't a `@WebMvcTest` prove about security?** Actuator rules and real credentials.
4. **How do you test that the app refuses to start without a secret?** `ApplicationContextRunner` with just that configuration and the profile; plus an unlisted profile to prove it's an allow-list.
