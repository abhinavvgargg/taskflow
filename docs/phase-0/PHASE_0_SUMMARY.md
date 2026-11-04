# Phase 0 — Foundations (Summary)

> **One page for the whole phase:** what was built, what it taught, what was decided, and what to say about it in an interview. Built 2026-09-21 → 2026-09-23.
> **Where the detail lives:** the brief with every trap ([PHASE_0_REQUIREMENTS.md](PHASE_0_REQUIREMENTS.md)), the full record of what was learned ([PHASE_0_LEARNING_LOG.md](PHASE_0_LEARNING_LOG.md), covering §0.1–§0.8), the test patterns ([TESTING_GUIDE.md](TESTING_GUIDE.md), §0.10), and the topic pages in [`knowledge/`](../knowledge/README.md), which explain each mechanism across all phases. This summary links to them and doesn't replace them.

---

## 1. The phase in one paragraph

Phase 0 built no business features. It built the skeleton every later phase plugs into. One small resource, **organizations**, goes through the whole stack: a validated request, a service that owns the transaction, a JPA entity, PostgreSQL with a Flyway-managed schema, and a paginated response. Every error comes back in one consistent format, and every request carries a correlation id into the logs. Tests run against real Postgres. The point was to make the expensive-to-change things right on day one: the error contract, the id strategy, config validation, schema discipline. Retrofitting any of them in month three costs a week. The phase's main lesson: **four bugs compiled, started and quietly did the wrong thing.** None of them crashed, which is why tests and startup checks matter.

---

## 2. What we built

| § | Built | Key files | Mechanisms explained in |
|---|---|---|---|
| 0.1 | Project skeleton: Boot 3.5.16, Java 21, Maven, package-by-feature (`common/` + one package per feature) | `pom.xml`, `common/{config,error,web,logging,persistence}`, `organization/` | [spring 01](../knowledge/spring/01-boot-configuration-and-beans.md) |
| 0.2 | Postgres 17 via Docker Compose: pinned image, named volume, healthcheck | `compose.yaml`, `.env.example` | [spring 04](../knowledge/spring/04-observability-and-operations.md) |
| 0.3 | Profiles (`dev`/`test`/`prod`) and typed, validated configuration | `application{,-dev,-test,-prod}.yml`, `ApiProperties` | [spring 01](../knowledge/spring/01-boot-configuration-and-beans.md) |
| 0.4 | Flyway, the first migration, schema naming conventions | `V1__create_organizations.sql`, `global_id_seq` | [jpa 06](../knowledge/jpa/06-schema-constraints-and-invariants.md) |
| 0.5 | `BaseEntity` (id + audit columns) and JPA auditing | `BaseEntity`, `AuditingConfig`, `AuditAwareImpl`, `Organization` | [jpa 01](../knowledge/jpa/01-entities-and-mapping.md), [jpa 07](../knowledge/jpa/07-auditing-and-time.md) |
| 0.6 | Web contracts: `POST`/`GET`/list for organizations, record DTOs, our own page envelope, capped page size, stable sort, sort whitelist | `OrganizationController`, `OrganizationService`, `PageResponse`, `PageableFactory`, `PageQuery` | [spring 02](../knowledge/spring/02-web-layer-validation-and-errors.md), [jpa 04](../knowledge/jpa/04-queries-projections-and-paging.md) |
| 0.7 | Global error handling: RFC 7807 `ProblemDetail` + stable error codes | `GlobalExceptionHandler`, `ErrorCode`, `CommonErrorCode`, `OrganizationErrorCode`, `ApplicationException` + subtypes | [spring 02](../knowledge/spring/02-web-layer-validation-and-errors.md) |
| 0.8 | Correlation id filter and structured logging (readable in dev, ECS JSON in prod) | `CorrelationIdFilter` | [spring 03](../knowledge/spring/03-filters-interceptors-and-request-scope.md), [spring 04](../knowledge/spring/04-observability-and-operations.md) |
| 0.9 | Actuator: health/info/metrics only, liveness/readiness probes, graceful shutdown timeout, build + git info. **OpenAPI deferred** (now Phase 11) | `application.yml` (`management.*`) | [spring 04](../knowledge/spring/04-observability-and-operations.md) |
| 0.10 | The testing baseline: one test of each kind (unit, web slice, JPA slice, full integration) on Testcontainers | `OrganizationServiceTest`, `OrganizationControllerTest`, `OrganizationRepositoryTest`, `OrganizationApiIntegrationTest` | [testing 01](../knowledge/testing/01-test-strategy-and-slices.md), [jpa 09](../knowledge/jpa/09-testing-jpa.md) |

**Working end to end:**
- `POST /api/v1/organizations` → 201 + `Location`, with the audit columns filled in.
- `GET /{id}` → 200 or 404.
- The list endpoint is paginated, capped at 100 and stable-sorted, and runs **2 SQL queries**.
- Every error is a `ProblemDetail` with a `code` and a `correlationId` that also appears in the logs.

---

## 3. Concepts learned

Each line covers what the concept is, why it matters, and where it shows up in TaskFlow.

**Spring Boot and configuration** → [spring 01](../knowledge/spring/01-boot-configuration-and-beans.md)
- **Auto-configuration is conditional, not magic.** Boot's `@AutoConfiguration` classes switch on only when their `@ConditionalOn…` checks pass. `@ConditionalOnMissingBean` is why your own `@Bean` wins over Boot's. To see the match report, run `-Dspring-boot.run.arguments=--debug`, not `--debug` on Maven.
- **Component scan starts at the main class's package.** Move `TaskflowApplication` and beans outside its package silently vanish.
- **Typed configuration.** `@ConfigurationProperties` on a validated record fails at **startup**, naming the property, the bad value and the rule. A mistyped `@Value` instead fails at 3am on the path nobody tested. Rules that involve two fields go in the record's compact constructor.
- **Profiles merge.** The base file always loads and the profile file overrides it. Environment variables beat YAML, which is what makes 12-factor config work.
- **No defaults for secrets.** Use `${DB_PASSWORD}`, never `${DB_PASSWORD:dev}`, so a misconfigured deploy refuses to start.

**Persistence (JPA, Flyway, Postgres)** → [jpa 01](../knowledge/jpa/01-entities-and-mapping.md), [jpa 06](../knowledge/jpa/06-schema-constraints-and-invariants.md), [jpa 07](../knowledge/jpa/07-auditing-and-time.md)
- **Flyway runs before Hibernate starts**, so `ddl-auto=validate` is a real guard: an entity with no matching migration means the app won't start. It checks columns and types, **not** nullability.
- **Applied migrations are immutable.** Checksums enforce it, so you fix forward with a new version. A file name with one underscore is silently ignored.
- **Name every constraint** (`uk_organizations_slug`). The name is the one reliable thing in a database error, and it's what maps to a clean 409.
- **`timestamptz` stores an absolute UTC instant, not a zone**, so `Instant` is the right Java type. `Instant` holds nanoseconds and Postgres keeps microseconds, so never compare round-tripped timestamps exactly.
- **Pooled sequences** (`increment by 50` = `allocationSize = 50`) mean one database round-trip per 50 ids. Gaps in the ids are normal: a sequence guarantees uniqueness, not contiguity.
- **`@MappedSuperclass` vs `@Embeddable` vs `@Inheritance`:**
  - `@MappedSuperclass` shares mapping but has no table of its own. That's `BaseEntity`.
  - `@Embeddable` is a value object stored as columns of another table.
  - `@Inheritance` is a real, queryable class hierarchy (Phase 5).
- **Auditing needs `@EntityListeners`**, and `@EnableJpaAuditing` belongs on its own `@Configuration` class.
- **Some JPA annotations only matter for DDL generation.** `unique`, `length` and `initialValue` do nothing under `validate`. `insertable`, `updatable`, `sequenceName` and `allocationSize` change runtime behaviour.

**Transactions and the session** → [jpa 02](../knowledge/jpa/02-persistence-context-and-flushing.md), [jpa 03](../knowledge/jpa/03-transactions.md)
- **`@Transactional` works through a proxy.** A call from inside the same class, or to a private method, runs with **no transaction** and no error.
- **`open-in-view=false`:** the Hibernate session closes when the service method returns. Map entity → DTO inside the transaction, or lazy access fails loudly (which is what you want).
- **`readOnly = true` on queries** skips dirty-checking and keeping snapshots.

**The web layer and errors** → [spring 02](../knowledge/spring/02-web-layer-validation-and-errors.md), [jpa 04](../knowledge/jpa/04-queries-projections-and-paging.md)
- **Entities never cross the web layer**, for three reasons:
  - Coupling: the JSON would become the schema.
  - Over-exposure: every field gets published, which in Phase 1 means the password hash.
  - Trusting input: accepting an entity means accepting a client-supplied `id` and audit fields.
- **Three layers of validation:**
  - The boundary (`@Valid`) rejects malformed input cheaply.
  - The service enforces rules that need database state.
  - The **constraint** catches the race between checking and inserting.
- **Pagination:**
  - Return our own `PageResponse`, never Spring's `PageImpl`; its JSON is an internal class, not a contract.
  - Cap the page size.
  - Always end the sort with a tiebreaker (`id`), or pages repeat or skip rows.
  - Offset paging gets slower the deeper you page; keyset paging is the answer at scale.
- **`ResponseEntityExceptionHandler`** already handles every MVC exception and sends them all through `handleExceptionInternal`. Call `super`, then **add to** the `ProblemDetail` it built.
- **Two kinds of error:**
  - Domain errors carry their own code.
  - Protocol errors (405, 415, bad JSON) are already described by their HTTP status, so the code is derived from the status.
- **`@RestControllerAdvice` never sees exceptions thrown in filters**, including Spring Security's 401/403. Those needed their own handlers (built in Phase 2).

**Logging and operations** → [spring 03](../knowledge/spring/03-filters-interceptors-and-request-scope.md), [spring 04](../knowledge/spring/04-observability-and-operations.md)
- **Filter vs interceptor:**
  - A `Filter` runs for everything, including security rejections and error dispatches.
  - A `HandlerInterceptor` only runs once MVC has picked a controller.
  - That's why the correlation id is a filter: 401s and 403s are exactly the responses you need to trace.
- **`OncePerRequestFilter`** makes sure an internal `FORWARD`/`ERROR` dispatch doesn't give one request two ids.
- **MDC is a `ThreadLocal`, and Tomcat reuses threads.** Without `MDC.remove` in `finally`, the next request inherits the last one's id. The same hazard comes back in Phase 3 (tenant context) and Phase 9 (`@Async`).
- **Boot 3.4+ logs structured JSON natively** (`logging.structured.format.console=ecs`). MDC values become fields you can query.
- **Liveness vs readiness:**
  - Liveness failing means "restart me"; readiness failing means "stop sending me traffic."
  - The database check must never be in liveness: a 10-second database blip would restart every instance at once.

**Testing** → [testing 01](../knowledge/testing/01-test-strategy-and-slices.md), [jpa 09](../knowledge/jpa/09-testing-jpa.md)
- **No H2.** It gets locking, constraint error codes and index behaviour wrong, so tests would pass against a database you don't ship.
- **`@ServiceConnection`** wires a Testcontainers database without `@DynamicPropertySource` boilerplate.
- **Context caching:** every distinct test configuration starts a whole new application, and its own container. This is why Spring test suites get slow.
- **Without flush + clear, a repository test never touches the database**, because the persistence context answers from memory.
- **Exception translation happens at the repository proxy.** That's why `DataIntegrityViolationException` exists for the handler to catch.

---

## 4. Decisions

The decisions you'd have to defend. All of them are indexed by topic in [decisions/INDEX.md](../knowledge/decisions/INDEX.md).

| Decision | Chosen | Why | What we turned down, and why |
|---|---|---|---|
| Boot version | 3.5.16 (generated with 4.x, then downgraded) | It's what the tutorials and the job market run | Boot 4: Initializr only serves 4.x now, but the learning material is all 3.x. The downgrade wasn't one line, because Boot 4 renamed the starters. |
| Lombok | Allowed, **never on entities** | `@Slf4j` and `@RequiredArgsConstructor` save real boilerplate | `@Data`/`@EqualsAndHashCode` break the hash contract for generated ids. `@ToString` walks lazy associations. `@Builder` removes the no-arg constructor JPA needs. |
| Primary key | `bigint` from one shared sequence, pooled by 50 | 8-byte foreign keys, batch-friendly inserts, good index locality | `IDENTITY`: one round-trip per insert and no JDBC batching. UUID PKs: 16-byte keys and scattered inserts. A public opaque id can be added later; changing the PK type can't. |
| Package layout | By feature; a feature never imports another feature | Cross-feature dependencies become visible | By layer: four packages to edit for every change. This rule forced the `ErrorCode` design below. |
| Error codes | An `ErrorCode` **interface** in `common`, one enum per feature | Lets `common/error` handle every error without importing any feature | A constraint → code registry in `common`: it would make `common` depend on every feature. |
| Error handler | Extend `ResponseEntityExceptionHandler`, override `handleExceptionInternal` | Every MVC exception handled for free, through one place that stamps `code` and `correlationId` | A plain advice: lots of typing to re-handle what Spring already does. |
| `created_by` holds | The **username** | Stays stable when the email changes, and keeps email (PII) out of every audit column | Email: a GDPR erasure would mean scrubbing every table. |
| Slug | Supplied by the client | Makes the 409 a real case to handle | Derived from the name: needs a slugify function *and* collision handling (`acme-2`). |
| Entity → DTO mapping | A static factory on the response record | The right size for one entity | MapStruct: out of scope for Phase 0. |
| Building the `Pageable` | By hand, from `ApiProperties` | One source of truth for API config | Spring's built-in resolver as well: two sources of truth. |
| Correlation id | A filter at highest precedence | It has to run for security rejections | An interceptor: never runs for 401/403. |
| `@Version` | **Not** on `BaseEntity`; per entity from Phase 5 | Optimistic locking is chosen where it fits | On every entity: lookup and audit tables would throw `OptimisticLockException` for no clear reason. |
| `equals`/`hashCode` on entities | None, deliberately (until Phase 5) | IDE-generated versions break once a generated id is assigned | Generated now: an entity gets lost inside a `HashSet` after persist. |
| OpenAPI | Deferred; springdoc taken off the classpath (now Phase 11) | Learn it properly; document security once, as bearer | Doing it now: it was serving Swagger UI under `prod`, and Phase 3–4 reshape the API. |

---

## 5. Patterns to reuse

These became rules for every later phase.

1. **Fail at startup, not in production.** Validated typed config, no secret defaults, `ddl-auto=validate`.
2. **Let the database enforce invariants.** The service check gives a friendly error; the named constraint guarantees correctness; both map to the same 409.
3. **One error format for everything**, built in one place, with a stable code clients can program against.
4. **A 500 says nothing.** The client gets a generic body plus the correlation id. The full stack trace goes to the logs. `include-stacktrace`/`include-message` are set to `never`.
5. **`common` never imports a feature.** Shared code declares an interface and features implement it.
6. **Transactions live in services**, never controllers, with `readOnly` on queries.
7. **Whitelist what the client controls**, such as sortable fields. Otherwise a bad value is a client-triggered 500 and, later, a client-chosen join.
8. **Clean up thread-bound state in `finally`.**
9. **Log one line per request at INFO**, so a correlation id always has something to match. Never log bodies, tokens or query strings.

---

## 6. Bugs, traps and deliberate failures

**Bugs that didn't crash.** This was the theme of the phase; full story in the [learning log §2](PHASE_0_LEARNING_LOG.md).

| Bug | What happened | Rule now |
|---|---|---|
| `@Max(100)` on a `String` | Hibernate Validator parsed the name as a number, so **every** real organization name was rejected | Use `@Size` for strings. A web-slice test now catches it (mutation-checked). |
| `org.hibernate.sql` (lowercase) | Logger names are case-sensitive, so nothing was logged | It's `org.hibernate.SQL` |
| `insertable = false` on a `NOT NULL` column | The app started fine and the first INSERT failed. `validate` doesn't check nullability | Only use `insertable = false` when the database fills the column |
| `${DB_PASSWORD:taskflow_local_dev}` | A prod deploy with no config would have started with the dev password | No defaults for secrets |
| Lombok under JDK 25 | JDK 23+ turns off implicit annotation processing, so Lombok produced nothing and you got "cannot find symbol" everywhere | Declare Lombok in `annotationProcessorPaths` |

**Traps worth remembering**
- **Check-then-insert races.** 30 concurrent POSTs with the same slug gave 4 × 500 from the constraint. At 6 requests it never showed: failing to reproduce a race isn't evidence it doesn't exist. → [jpa 05](../knowledge/jpa/05-bulk-updates-and-concurrency.md)
- **Spring Data skips the count query** on a short first page, so measure with more rows than the page size.
- **`?sort=doesNotExist` gave a 500.** Fixed with a per-endpoint whitelist.
- **Tests:**
  - `@DataJpaTest` doesn't load your auditing config (`@Import` it).
  - Integration-test data commits instead of rolling back.
  - Tests inherit the `dev` profile unless `@ActiveProfiles` says otherwise.
- **`git commit -a`** swept unrelated doc edits into commits twice, so stage files by name.

**Deliberate failures:** broken on purpose to see the error.

| Broke | What you saw | Lesson |
|---|---|---|
| `max-page-size: 0` | Startup failed, naming the property, the value and the rule | Typed config pays off |
| `default-page-size` > `max-page-size` | Startup failed from the compact constructor | Cross-field rules live in the record |
| `prod` profile with no env vars | Startup failed on an unresolved placeholder | No secret defaults works |
| An entity field with no migration | Startup failed on `validate` | What schema validation guarantees… |
| `insertable = false` on a `NOT NULL` column | Startup **passed**, then the first insert failed | …and where it stops |
| 30 concurrent POSTs, same slug | 1 × 201, 29 × 409, **zero 500s** after the fix | The constraint is the real guard |

---

## 7. Interview questions

Short answers, the way you'd say them. All 25 are in the [learning log §5](PHASE_0_LEARNING_LOG.md); the testing ones are in [TESTING_GUIDE.md §9](TESTING_GUIDE.md).

1. **How does Boot decide which auto-configurations apply?** "Each auto-configuration class has conditions, like 'this class is on the classpath' or 'no bean of this type exists yet'. If I define my own bean, the 'missing bean' condition fails and mine wins. Running with `--debug` prints which ones matched and why."
2. **Why Flyway over `ddl-auto=update`?** "Three reasons: migrations are versioned and reviewed in git; Hibernate never guesses at destructive changes; and `update` only ever adds, so production slowly drifts from the code. I pair it with `validate`, so a missing migration stops the app at startup."
3. **What does `open-in-view=false` change?** "The session closes when the service returns. Lazy loading outside a transaction fails loudly instead of firing hidden queries during JSON serialization while holding a connection for the whole request."
4. **Filter or interceptor for a correlation id?** "A filter, ordered first. An interceptor runs only after MVC picks a controller, so it would never see a request that security rejected, and those are the ones you most need to trace."
5. **Walk me through your error contract.** "Every error is an RFC 7807 `ProblemDetail` with a stable code, structured fields and the correlation id. Framework and domain errors both pass through one method, so they all look the same."
6. **Where can a 500 leak information?** "Exception messages carry table names, SQL, file paths and library versions. The client gets a generic message and the correlation id; the full trace goes to the logs."
7. **How do you handle a race on a unique value?** "The service check catches the common case with a nice error. Two requests can both pass it, so the named unique constraint is the real guard, and I map its violation to the same 409. I proved it with 30 concurrent requests: zero 500s."
8. **Why is `@Data` dangerous on an entity?** "Its `equals`/`hashCode` change once the database assigns an id, so the entity gets lost in a `HashSet`. Its `toString` walks lazy associations and can fire queries or throw."
9. **What causes unstable pagination?** "Sorting on a value that isn't unique. Rows that tie have no defined order, so page 2 can repeat or skip rows. Always end the sort with the id."
10. **Why not H2 in tests?** "It behaves differently from Postgres on locking, constraint error codes and indexes, so tests pass against a database you don't ship. Testcontainers gives the real one."
11. **Why is a Spring test suite slow?** "Every distinct test configuration starts its own application context. Aligning configurations lets them share one. I took ours from three containers to two."
12. **Why `IDENTITY` is a problem at scale.** "It needs a database round-trip for every insert and silently turns off JDBC batching. A pooled sequence hands out 50 ids per round-trip."

---

## 8. Numbers measured

| What | Result |
|---|---|
| SQL for the list endpoint | **2 queries** (page + count), the baseline for Phase 8's N+1 work |
| Concurrent duplicate-slug POSTs | 30 requests → 1 × 201, 29 × 409, 0 × 500 |
| Test suite at end of phase | **13 tests, 8 s, 2 containers** (3 before aligning `TaskflowApplicationTests` with the integration test) |
| Mutation check | Putting the `@Max`-on-a-String bug back makes the 201 test fail in 0.4 s |

---

## 9. Deferred and carried debt

Where each item went. Status checked against the code on 2026-10-28.

| Item | Where it went |
|---|---|
| Auditor returning `"system"` | ✅ Phase 1 §1.2: now the authenticated user's username |
| Read the `--debug` auto-configuration report | ✅ Phase 1 §1.1 |
| Never echo `rejectedValue` | Half done: the handler never echoes values (confirmed in Phase 1). **The test proving it is still owed**: Tier-2 test debt, moved to Phase 11 (Phase 3 §3.0) |
| A home for the security audit log | ✅ Phase 1 §1.5 (`security_events`) |
| 401/403 bypassing the advice | ✅ Phase 2 (`ProblemAuthenticationEntryPoint`, `ProblemAccessDeniedHandler`) |
| Public no-arg constructor on `Organization` | ✅ Phase 3 §3.1 (now `protected`) |
| Unknown paths leaking `"No static resource …"` | ✅ Now a generic "No resource found" |
| OpenAPI | ⏸️ Phase 11, alongside README and Postman. springdoc 2.9.1 (3.x is for Boot 4); the four fixes are in requirements §0.9 |
| `prometheus` endpoint | ⏸️ The observability phase, when a registry and a scraper exist |
| README (stack, run, API and DB conventions) | ⏸️ Phase 3 §3.5 |
| Slug's `@Size(min = 3)` stricter than the database `CHECK` (one character allowed) | Open |
| Sort whitelist | Partly done: `name` is now sortable, `slug` still isn't |
| `CorrelationIdFilter` does two jobs (id + completion log) | Open; split it if it grows |
| Type-mismatch messages leaking `java.lang.Integer`; multiple errors per field returned ungrouped; the `@Pattern` message echoing the raw regex | From the Phase 0 log; not re-checked |
| `/actuator/info` empty when IntelliJ builds the app | Known: run Maven's `generate-resources` before building. Keep `git.mode: simple` (`full` exposed an email and an internal IP) |
| Your 15-minute Phase 0 notes | Template at the end of the requirements doc is still empty |

---

## 10. Read more

- **Rebuild a feature:** [features/INDEX.md](../knowledge/features/INDEX.md) (Phase 0 rows point to requirements §0.1–§0.10)
- **Why we decided X:** [decisions/INDEX.md](../knowledge/decisions/INDEX.md)
- **A mechanism in depth:** [knowledge/README.md](../knowledge/README.md), and [knowledge/jpa/SOURCES.md](../knowledge/jpa/SOURCES.md) / [knowledge/SOURCES.md](../knowledge/SOURCES.md) to find where each Phase 0 lesson landed
- **Commands** (compose, psql, `--debug`, reading Boot's own config): [learning log §6](PHASE_0_LEARNING_LOG.md)
- **Writing a test:** [TESTING_GUIDE.md](TESTING_GUIDE.md)
