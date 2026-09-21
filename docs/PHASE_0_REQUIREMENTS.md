# Phase 0 — Foundations (Requirements)

> Companion to `PROJECT_CONTEXT.md`. Requirements only — no code. Tick the checklist as you go.
> **Time-box: 6–7h. Hard stop at 10h (150%).** Whatever isn't done carries into Phase 1 as a side task; the phase does not extend.

**The goal of Phase 0 is not features.** By the end, one trivial endpoint goes through the whole stack — validated request → service → JPA → Postgres via a Flyway-managed schema → paginated response, with a correlation ID in the logs, a documented error contract, and a Testcontainers test that proves it. Every later phase then adds business logic into a skeleton that is already production-shaped.

⚠️ **Trap, the one that kills these projects:** building Phase 0 "properly later". Every item below is cheap now and a week of retrofitting in month three. The error contract and the ID strategy especially — those leak into every table and every response you will ever write.

---

## Decisions — fill these in before session 1

| # | Decision | Chosen | Why (one line, for the interview) |
|---|---|---|---|
| 1 | Build tool | **Maven** 3.9.16 | Standard in enterprise Spring shops; what a take-home hands you. |
| 2 | Boot version | **3.5.16** | Matches every tutorial/blog you'll read for 12 weeks; what the job market runs. |
| 3 | Lombok | **Full, but never on entities** | `@Slf4j` + `@RequiredArgsConstructor` only; `@Data`/`@ToString`/`@Builder`/`@EqualsAndHashCode` banned on JPA entities. |
| 4 | Primary key strategy | _open — sequence vs UUIDv7_ | |
| 5 | Phase 0 vertical slice | **`organizations`** (id, name, slug + audit) | Real table you keep; zero throwaway code. |
| 6 | Java version | **21** (Homebrew `openjdk@21`) | Plan + resume say 21; avoids stacking Lombok/Boot/JDK bleeding edges. |

### Lombok rules (decision 3, made concrete)

**Allowed:** `@Slf4j` anywhere · `@RequiredArgsConstructor` on services/controllers/components · `@Getter` on entities if wanted.

**Banned on entities:** `@Data`, `@Value`, `@EqualsAndHashCode` (breaks the hashCode contract for generated IDs), `@ToString` (touches lazy associations — with `open-in-view=false` a log statement throws `LazyInitializationException` or fires an N+1), `@Builder` (removes the no-arg constructor JPA requires), class-level `@Setter` (audit fields must not be settable).

DTOs are records, so Lombok does not apply there.

### ⚠️ Initializr no longer generates Boot 3

start.spring.io rejects anything below 4.0.0 (`"Spring Boot compatibility range is >=4.0.0"`). Generate with Boot 4.x, then downgrade the pom. **Boot 4 renamed the starters**, so it is not a one-line change:

| Boot 4 name | Boot 3.5 equivalent |
|---|---|
| `spring-boot-starter-webmvc` | `spring-boot-starter-web` |
| `spring-boot-starter-flyway` | *(none)* → `org.flywaydb:flyway-core` |
| `spring-boot-starter-{webmvc,data-jpa,flyway,validation,actuator}-test` | one `spring-boot-starter-test` |

## Concepts you need first (the *why*, briefly)

💡 **Auto-configuration** — Boot's `@Conditional`-driven guesses. `spring-boot-autoconfigure` ships hundreds of `@AutoConfiguration` classes listed in `META-INF/spring/…AutoConfiguration.imports`; each activates only if its conditions hold (`@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`). **Why it matters:** you must be able to say *why* a bean exists and how to override it. "Boot magic" is a bad interview answer; "there is a conditional on missing bean, so my `@Bean` wins" is a good one.

🔍 **Look inside:** start the app with `--debug` and read the auto-configuration report — *Positive matches* / *Negative matches* / *Exclusions*. Do this once in Phase 0 and you will never be mystified by Boot again. Find `DataSourceAutoConfiguration` and `JpaRepositoriesAutoConfiguration` in the positive list and note *which condition* let them in.

💡 **Typed configuration** — `@ConfigurationProperties` binds a whole prefix onto a constructor-bound record/class, validated with Bean Validation at startup. **Why:** `@Value("${...}")` scattered across classes gives you typos that surface at 3am on the one code path nobody tested. Typed + validated config fails at *startup*, in CI, not in production.

💡 **Filters vs interceptors** — a `Filter` is Servlet-spec, sits in the container's chain, and wraps *everything* including Spring Security and error dispatches. A `HandlerInterceptor` is Spring MVC, runs inside `DispatcherServlet` after handler mapping, and knows which controller method was selected. **Why it decides your correlation ID:** you want the ID present for security failures and for responses that never reach a controller — so it must be a filter, ordered early.

💡 **`open-in-view=false`** — Boot defaults this to `true`: it holds the Hibernate `Session` open for the whole request so lazy collections can be walked *in the view/serialisation layer*. **Why off:** it hides N+1 (they happen during JSON serialisation where no test looks), holds a DB connection for the entire request including slow client writes, and lets `LazyInitializationException` stay hidden until the day you move logic. Turning it off means lazy access outside a transaction fails loudly — exactly what you want while learning.

💡 **Testcontainers** — real Postgres in Docker, started by the test JVM, thrown away after. **Why no H2:** H2 lies about row locking (Phase 5 needs `SELECT … FOR UPDATE` semantics), has different `jsonb`, different partial-index support, and different constraint-violation error codes — so your error-mapping tests would pass against a database you do not ship.

---

## 0.1 — Repo & project skeleton (~45 min)

- [ ] `git init` at `~/IdeaProjects/taskflow` (it is not a repo yet). `.gitignore` covering `target/`, IDE files, `.env`, `.DS_Store`.
- [ ] Java 21 pinned via the build's toolchain — not "whatever JDK is on PATH".
- [ ] Base package `com.<yourname>.taskflow`. **Package-by-feature**, not by layer:

```
taskflow/
  TaskflowApplication.java
  common/            ← shared; no feature depends on another feature
    config/  error/  web/  logging/  persistence/
  organization/      ← controller, service, repository, dto, domain — all here
```

- [ ] **Rule enforced by hand:** a feature package never imports from another feature package. Shared things move to `common/`. Why: this makes a Phase 3 `organization` → Phase 5 `issue` dependency visible instead of accidental.
- [ ] ⚠️ **Trap:** the tutorial layout (`controller/`, `service/`, `repository/`, `model/`). Tidy at 5 classes; at 50 it is four packages you edit for every single change. You will be asked why you chose your layout.
- [ ] Dependencies for Phase 0 **only**: web, validation, data-jpa, flyway-core + flyway-database-postgresql, postgresql driver, actuator, springdoc-openapi-starter-webmvc-ui; test scope: spring-boot-starter-test, testcontainers (postgresql + junit-jupiter), spring-boot-testcontainers.
- [ ] **No security starter yet** — adding it now silently locks every endpoint and costs you a confused hour. That is Phase 1.
- [ ] README exists from commit #1: what TaskFlow is, the stack, how to run. Two paragraphs is enough today.

**Done when:** the build passes and the app starts.

---

## 0.2 — Postgres via Docker Compose (~30 min)

- [ ] `compose.yaml` at repo root: Postgres 17, named volume for data, a **healthcheck** (`pg_isready`), credentials from env with no production-usable defaults.
- [ ] `.env.example` committed with every variable and dummy values. Real `.env` gitignored.
- [ ] Healthcheck-gated startup: the app must not boot against a Postgres that is still initialising.

💡 **Concept — `spring-boot-docker-compose`:** Boot 3.1+ can start your compose file on run and *derive* the datasource URL and credentials from the running container, so `application-dev.yml` needs no JDBC URL at all. **Buys:** nobody can run the app against the wrong database; dev setup is clone-and-run. **Costs:** the connection config becomes invisible, which is confusing while learning.
🏗️ Your call — recommended: add it, **and** write the explicit dev datasource config in a comment so you know what it is doing.

**Done when:** `docker compose up -d` gives a healthy container and the app connects.

---

## 0.3 — Profiles & typed configuration (~1h)

- [ ] Three profiles: `dev`, `test`, `prod`. `application.yml` holds only what is identical everywhere; the rest in `application-{profile}.yml`.
- [ ] **`spring.jpa.open-in-view=false`** in the base `application.yml`. Day one, as agreed.
- [ ] **`spring.jpa.hibernate.ddl-auto=validate`** in dev and prod. Never `update`. `validate` fails startup when an entity and a migration disagree — catching "I added a field and forgot the migration" in CI rather than at runtime.
- [ ] `spring.jpa.show-sql=false`; use `logging.level.org.hibernate.SQL=DEBUG` plus binder logging in dev. Why: `show-sql` writes to stdout unformatted, bypassing your logging config entirely — so it would bypass your JSON logs and correlation IDs too.
- [ ] **`prod` has no hardcoded secrets and no defaults for them.** A missing `DB_PASSWORD` must fail startup, not fall back.
- [ ] One `@ConfigurationProperties` class: `taskflow.api` with `default-page-size` and `max-page-size` at minimum. Constructor-bound record, registered via `@ConfigurationPropertiesScan` or `@EnableConfigurationProperties`, annotated `@Validated`, fields carrying `@Min`/`@Max`/`@NotBlank`.
- [ ] ⚠️ **Trap — create this bug on purpose, then read the error:** set `max-page-size: 0` and start the app. You should get a startup failure naming the property, the invalid value, and the violated constraint. **Feel that failure once** — it is the entire argument for typed config over `@Value`, and you will repeat it in an interview from memory.
- [ ] HikariCP `maximum-pool-size` set explicitly per profile rather than taking the default 10. 💡 Pool sizing is a real interview topic — bigger is not faster; the pool should be small and the queue should do the waiting.
- [ ] 🔍 **Look inside:** run once with `--debug`, find `JpaBaseConfiguration`, and identify which `@ConditionalOnMissingBean` would let you replace the `EntityManagerFactory`.

🎯 **Interview question:** "How does Spring Boot decide which auto-configurations to apply, and how would you override one?"

---

## 0.4 — Flyway & schema conventions (~1h)

- [ ] Flyway enabled, migrations in `db/migration`, naming `V1__create_organizations.sql`.
- [ ] `spring.flyway.clean-disabled=true` — non-negotiable in every profile. One misconfigured CI job with clean enabled drops production.
- [ ] `V1` creates `organizations`:
  - [ ] Surrogate PK per decision #4.
  - [ ] `name` — `varchar`, NOT NULL, length limit chosen deliberately (not 255-because-default).
  - [ ] `slug` — NOT NULL, **UNIQUE**, lowercase, with a `CHECK` constraint on the allowed character pattern.
  - [ ] Audit columns `created_at`, `updated_at` as **`timestamptz`**, NOT NULL; `created_by`, `updated_by`.
- [ ] 💡 **Concept — `timestamptz` vs `timestamp`:** `timestamp` has no zone and silently means "whatever the server thought". `timestamptz` stores an absolute instant. Store UTC, convert at the edge. Getting this wrong is invisible until you have users in a second timezone, and then it is unfixable without a data migration.
- [ ] **Every constraint gets an explicit name**: `pk_organizations`, `uk_organizations_slug`, `ck_organizations_slug_format`. Why (this is the payoff): when Postgres throws a unique violation, the constraint *name* is the only reliable thing in the exception — you will map `uk_organizations_slug` → error code `ORGANIZATION_SLUG_TAKEN` → HTTP 409. Auto-generated names make that mapping fragile.
- [ ] Conventions written into the README and never broken: `snake_case`, plural table names, singular column names, `id` as PK, `<table>_id` for FKs, prefixes `pk_ uk_ fk_ ix_ ck_`.
- [ ] **Rule: an applied migration is immutable.** Fix forward with `V2`. Flyway checksums enforce this; understand what a checksum mismatch means before you hit it at 11pm.
- [ ] ⚠️ **Trap:** letting Hibernate create the schema "just for now" in dev. The moment dev and migration diverge, `validate` catches it — which is why `validate` is required above.

🎯 **Interview question:** "Why Flyway over `ddl-auto=update`?" Needs *three* reasons: reviewability/versioning, no data-destructive guesses, and the fact that `update` never drops or narrows anything, so prod slowly diverges from your code.

---

## 0.5 — `BaseEntity` & JPA auditing (~45 min)

- [ ] `BaseEntity` as a `@MappedSuperclass` in `common/persistence`: id, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`. Audit fields not settable from outside — no public setters.
- [ ] JPA auditing enabled (`@EnableJpaAuditing` + `AuditingEntityListener` reaching the entity). An `AuditorAware` returning a fixed `"system"` for now, with `Optional.empty()` handling defined — Phase 1 swaps in the authenticated user with a one-line change.
- [ ] 💡 **Concept — `@MappedSuperclass` vs `@Embeddable` vs `@Inheritance`:** `@MappedSuperclass` shares *mapping* with no table and no polymorphic queries; `@Embeddable` groups columns into a value object reusable as a field; `@Inheritance` creates a queryable type hierarchy with real table-strategy cost. You want the first here; you meet the third properly in Phase 5.
- [ ] ⚠️ **Trap — do not add `equals`/`hashCode` yet, and do not let the IDE generate them.** Generated-ID entities break the `hashCode` contract the moment a transient entity gets an ID after being added to a `HashSet`. Phase 5 covers this properly. Today the requirement is: none, deliberately.
- [ ] 🏗️ **`@Version` in `BaseEntity`?** Recommended: **no** — put it on specific entities in Phase 5 where you have decided optimistic locking is right. Blanket versioning makes lookup/audit tables throw `OptimisticLockException` for reasons nobody expects. Your call, justify it.
- [ ] The `organization` entity extends `BaseEntity`. Repository is a plain Spring Data interface — no custom methods needed yet.

---

## 0.6 — Web layer contracts (~1h)

- [ ] Endpoints: `POST /api/v1/organizations` (201 + `Location`), `GET /api/v1/organizations/{id}` (200/404), `GET /api/v1/organizations` (paginated).
- [ ] **DTOs are records**, request and response separate types. Entities never appear in a controller signature — not as parameter, not as return type.
- [ ] **Controllers do HTTP only:** bind, validate, delegate, map status. No repository access, no `@Transactional`, no business rules. Services own transactions — `readOnly = true` on queries.
- [ ] Validation at the boundary with `@Valid`; constraints on the record components. Business rules (slug uniqueness) in the **service**; the DB unique constraint is the third guard. Be able to name what each layer catches that the others do not — *the boundary catches malformed input cheaply, the service enforces rules needing DB state, the constraint catches the race between check and insert.*
- [ ] **Pagination envelope:** `PageResponse<T>` in `common/web` — content, page, size, totalElements, totalPages.
- [ ] ⚠️ **Trap:** returning Spring Data's `Page`/`PageImpl` straight out of a controller. Its JSON is a serialisation of an internal class, is not a stable contract, and Spring Data explicitly warns about it. You would be locked into Spring Data's shape forever.
- [ ] **Cap the page size** using `taskflow.api.max-page-size` — a client asking for `size=100000` gets clamped, not an OOM.
- [ ] **Stable sort is mandatory:** every paginated query ends with a deterministic tiebreaker (e.g. `id`).
- [ ] ⚠️ **Trap:** sorting by `createdAt` alone. Rows with equal timestamps have no defined order, so page 2 can repeat or skip rows page 1 already showed. Silent, intermittent, and a great interview story.
- [ ] API conventions in the README: plural nouns, no verbs in paths, `/api/v1` from day one, 201+`Location` on create, 204 on delete.

📊 **Measure it:** with `logging.level.org.hibernate.SQL=DEBUG`, hit `GET /api/v1/organizations` and **count the SQL statements**. Expect exactly two (count + page). Write the number down — it is the Phase 8 baseline.

**Measured baseline:** `____ queries for GET /api/v1/organizations`

---

## 0.7 — Global error handling (~1h 15m)

The highest-leverage hour in Phase 0. Every later phase just registers exceptions into the machinery you build now.

- [ ] One `@RestControllerAdvice`. Output is **RFC 7807 `ProblemDetail`** with extensions: a stable `code`, a `correlationId`, a `timestamp`, and for validation failures a structured list of field errors.
- [ ] Decide whether echoing `rejectedValue` is safe — it is not, for passwords.
- [ ] Handle, each with the correct status: bean-validation failure (400), constraint violation on path/query params (400), unreadable/malformed JSON (400), argument type mismatch (400), missing request parameter (400), `ResourceNotFoundException` (404), method not allowed (405), unsupported media type (415), duplicate/conflict (409), `DataIntegrityViolationException` (409 — mapped via the **constraint name** from 0.4), catch-all `Exception` (500).
- [ ] **The 500 handler is the one that matters:** log the full exception at ERROR *with the correlation ID*; return a ProblemDetail with a generic message and that correlation ID, and **nothing else**. No exception class name, no message, no SQL fragment, no stack trace. Exception messages leak schema, file paths and library versions — free reconnaissance.
- [ ] `server.error.include-stacktrace=never` and `include-message=never` in all profiles. ⚠️ **Trap:** these default to something friendlier in dev and get copy-pasted into prod.
- [ ] **Error codes as an enum**, not string literals — this is your public contract for clients doing programmatic error handling: `ORGANIZATION_NOT_FOUND`, `VALIDATION_FAILED`, `ORGANIZATION_SLUG_TAKEN`.
- [ ] ⚠️ **Trap you will hit in Phase 2 — note it now, do not fix it now:** `@RestControllerAdvice` **does not catch authentication and authorization failures** thrown inside the Spring Security filter chain; they happen before `DispatcherServlet` exists. You will need an `AuthenticationEntryPoint` and an `AccessDeniedHandler` to produce the same JSON shape.
- [ ] 🔍 **Look inside:** read `ResponseEntityExceptionHandler` in the Spring source. It already handles most MVC exceptions, one protected method each, and is ProblemDetail-aware in Spring 6.

🏗️ **Extend `ResponseEntityExceptionHandler` or write a plain advice?** Extending gives all MVC exceptions free plus one `handleExceptionInternal` hook to stamp `code`/`correlationId` on every response. Plain gives total control and more typing. Recommended: **extend**, override only `handleExceptionInternal`, add your own domain handlers.

🎯 **Interview questions:** "Walk me through your API error contract." · "Where would a 500 leak information, and how did you stop it?"

---

## 0.8 — Correlation ID & structured logging (~1h)

- [ ] A `OncePerRequestFilter` that: reads `X-Correlation-Id` or generates one; **validates** the incoming value (length cap, character whitelist) before using it; puts it in SLF4J **MDC**; echoes it on the response header; **clears the MDC in a `finally` block**.
- [ ] ⚠️ **Trap — the `finally` is not optional.** Tomcat pools threads. An MDC value you do not clear stays attached to that thread and is stamped onto the *next, unrelated* request's logs — you would debug with actively wrong evidence. The same thread-reuse mechanic bites again with `ThreadLocal` in Phase 3.
- [ ] ⚠️ **Trap — never trust a client-supplied header unvalidated.** An unbounded correlation ID is a log-injection / log-flooding vector; newlines in it can forge fake log lines.
- [ ] 💡 **Why `OncePerRequestFilter` and not a plain `Filter`:** a plain filter can run multiple times per request on an internal `FORWARD`/`ERROR` dispatch — two IDs for one request. `OncePerRequestFilter` guards against that with a request attribute.
- [ ] **Order at high precedence** so it wraps security and error dispatch. Know whether you registered via `@Component` + `@Order` or a `FilterRegistrationBean` (explicit order, plus URL scoping).
- [ ] 💡 **Filter vs interceptor, decided concretely** — write down *why this one had to be a filter*: an interceptor never runs if security rejects the request, so your 401s and 403s would have no correlation ID — exactly the responses you most need to trace.
- [ ] Logging config: human-readable pattern including `%X{correlationId}` in `dev`; **JSON in `prod`**.
- [ ] 💡 Boot 3.4+ ships **native structured logging** (`logging.structured.format.console=ecs`, or `gelf`/`logstash`) — no logstash-encoder dependency needed. Most tutorials predate this; note it.
- [ ] One request-completion log line per request: method, path, status, duration ms. **No bodies, no headers, no query strings containing tokens, no PII.**
- [ ] All log statements use **parameterised SLF4J** (`log.debug("Found {} orgs", count)`), never concatenation. Concatenation builds the string even when the level is disabled, and breaks structured-log field extraction.

🎯 **Interview question:** "A user reports a failed request at 14:32. Walk me through how you find it in the logs."

---

## 0.9 — OpenAPI & Actuator (~45 min)

- [ ] springdoc configured with real API info (title, version, description, contact). Every endpoint documents its success **and** error responses, referencing the ProblemDetail shape — a doc describing only happy paths is half a contract.
- [ ] Swagger UI available in `dev`, **disabled in `prod`** (or later, secured). It is a complete map of your attack surface.
- [ ] Actuator exposes **only** `health`, `info`, `metrics`, `prometheus`. Never `*` — `/actuator/env` and `/actuator/configprops` dump configuration, `/actuator/heapdump` dumps memory including secrets in it.
- [ ] `management.endpoint.health.show-details=when-authorized` (so today: not shown) — health details name your database and its state.
- [ ] Liveness and readiness probe groups enabled. 💡 Liveness failing means "restart me"; readiness failing means "stop sending me traffic, I am still alive". Conflating them causes restart loops under load.
- [ ] **Graceful shutdown** enabled with an explicit timeout — in-flight requests finish instead of being severed mid-transaction.
- [ ] `/actuator/info` populated from real build info via the build plugin — git commit and build time. "Which version is actually deployed" is the first question of every incident.

---

## 0.10 — The testing baseline (~1h 15m)

Four tests. Not coverage — one of each *kind*, so the pattern exists for every later phase to copy.

- [ ] **Unit test** — a service rule with a mocked repository, no Spring context. Runs in milliseconds.
- [ ] **`@WebMvcTest`** — controller slice, service mocked. Assert a bad request body produces your exact ProblemDetail JSON: right status, right `code`, field errors populated. This test locks the error contract down.
- [ ] **`@DataJpaTest` + Testcontainers Postgres** — assert `createdAt`/`createdBy` are populated by auditing, and that a duplicate slug throws (proving the DB constraint is real, not just entity annotations).
- [ ] ⚠️ **Trap:** `@DataJpaTest` replaces your datasource with an embedded one by default — you must stop it doing that.
- [ ] **`@SpringBootTest` + Testcontainers, full stack, real HTTP** — POST then GET; assert 201 + `Location`, then the paginated envelope's shape.
- [ ] 💡 **`@ServiceConnection` (Boot 3.1+)** on the container bean wires the datasource automatically, replacing the `@DynamicPropertySource` boilerplate every older tutorial shows. Use it, and know what it replaced.
- [ ] **Flyway runs in the test container.** This is the point: migrations are now tested on every build. A broken migration fails CI, not deploy.
- [ ] **One shared container across the suite** — a base test class, the singleton-container pattern, or reuse.
- [ ] 📊 **Measure it:** time the suite with a container per class vs shared. Write both numbers down.
- [ ] 💡 **Context caching:** Spring caches the `ApplicationContext` per unique configuration. Every distinct combination of profiles/mock beans/properties starts another full context. This is *the* reason slow test suites are slow, and the question that separates people who have run a real suite from people who have not.
- [ ] ⚠️ **No H2, ever** — concrete reasons for your notes: `SELECT … FOR UPDATE` semantics (Phase 5), Postgres constraint error codes (0.7 above), real index behaviour (Phase 8). H2 gets all three wrong in ways that make tests pass and production fail.

**Measured:** container-per-class `____s` → shared container `____s`

---

## Definition of done

Check these literally. Do not assume.

- [ ] `git clone` → `docker compose up -d` → run → `GET /actuator/health` is `UP`.
- [ ] `POST /api/v1/organizations` with a valid body → 201 + `Location`; the row is in Postgres with audit columns populated.
- [ ] The same POST twice → **409** with your error code, not a 500.
- [ ] An invalid body → 400 ProblemDetail with field errors and a correlation ID.
- [ ] `GET /api/v1/organizations?size=99999` → clamped, not OOM. Sort is deterministic.
- [ ] An unmapped exception → 500 with a correlation ID and **no** stack trace, class name, or SQL.
- [ ] Every response carries `X-Correlation-Id`; logs for that request carry the same value; a second request gets a different one.
- [ ] App **fails to start** when a `@ConfigurationProperties` value violates its constraint.
- [ ] App **fails to start** when an entity and the migration disagree (test it: add a field without a migration).
- [ ] `open-in-view=false`, `ddl-auto=validate`, `flyway.clean-disabled=true` — verified in the running config.
- [ ] Test suite green against Testcontainers; no H2 on the classpath at all.
- [ ] `/actuator` exposes exactly four endpoints; Swagger UI off in `prod`.
- [ ] README describes stack, how to run, API conventions, DB naming conventions.
- [ ] Commits are small and per-feature — roughly one per numbered section above.

---

## Explicitly NOT in Phase 0

Security starter · authentication · users · JWT · multi-tenancy · caching · MapStruct · a custom `@Aspect` · metrics beyond defaults · CI (Phase 11) · an abstract `BaseService`/`BaseController` (premature — you do not know the shape yet) · any refactor for elegance.

---

## 🎯 Interview questions — answer these *during* Phase 0, not after

1. How does Boot decide which auto-configurations apply, and how do you override one?
2. Why Flyway over `ddl-auto=update`? (three distinct reasons)
3. What does `open-in-view=false` change, and what breaks when you flip it?
4. Filter vs interceptor — and why is correlation ID specifically a filter?
5. Walk me through your API error contract. Where could a 500 leak information?
6. Why not H2 in tests? Name a specific behaviour it gets wrong.
7. Why is your Spring test suite slow, and what is context caching?
8. Why is `IDENTITY` a problematic ID strategy at scale?
9. Why is `@Data` dangerous on a JPA entity?
10. Unstable pagination — what causes it, and how did you prevent it?

---

## Session split (6–7h)

| Session | Sections | Est. | Actual |
|---|---|---|---|
| 1 | 0.1 + 0.2 + 0.3 — skeleton, Postgres, profiles, typed config | ~2h 15m | |
| 2 | 0.4 + 0.5 — Flyway, conventions, BaseEntity, auditing | ~1h 45m | |
| 3 | 0.6 + 0.7 — web contracts, error handling | ~2h 15m | |
| 4 | 0.8 + 0.9 + 0.10 — correlation ID, logging, ops, tests | ~2h 45m | |

Over budget? **Trim 0.9 first** — least conceptually dense.

**Before session 1:** the weekend revision from the plan — Marco Behler's `@Transactional` deep-dive and the Spring Security *Servlet Architecture* page. The first pays off in 0.6; the second you want loaded before Phase 1.

---

## Phase 0 notes (15 min at the end — these become interview stories)

**What I built:**

**What confused me:**

**What I learned:**

**Traps I actually hit:**
