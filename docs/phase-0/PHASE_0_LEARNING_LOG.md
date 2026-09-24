# Phase 0 — Revision & Learning Log (§0.1 – §0.8)

> Written 2026-09-22, covering everything built and learned from the empty repo through structured logging.
> Companions: `PHASE_0_REQUIREMENTS.md` (what to build) · `../PROJECT_CONTEXT.md` (the 12-week plan).

---

## 0. Decisions on record

| # | Decision | Chosen | The one-line justification |
|---|---|---|---|
| 1 | Build tool | Maven 3.9.16 | Standard in enterprise Spring shops; what a take-home hands you. |
| 2 | Boot version | 3.5.16 | Matches the tutorials and the job market. Initializr no longer offers it. |
| 3 | Lombok | Full, **never on entities** | `@Slf4j` + `@RequiredArgsConstructor` in practice. |
| 4 | Primary key | `bigint` + `global_id_seq`, `increment by 50` | 8-byte FKs, batch-insert friendly, index locality. Adding an opaque public ID later is cheap; changing the PK type is not. |
| 5 | Phase 0 slice | `organizations` | A real table kept for good; zero throwaway code. |
| 6 | Java | 21 (Homebrew `openjdk@21`) | Plan and resume say 21; avoids stacking Lombok + Boot + JDK bleeding edges. |
| 7 | `id` location | `BaseEntity`, one shared sequence | Less repetition. Trade-off: all tables share one number space. |
| 8 | Config format | YAML | Nesting across three profiles. |
| 9 | `created_by` holds | **Username**, not email | Stable identifier survives an email change; keeps `varchar(50)` viable. |
| 10 | Slug source | Client-supplied | Deriving needs slugify *and* collision handling; this makes the 409 a real case. |
| 11 | Entity→DTO mapping | Static factory on the response record | Right size at one entity. No MapStruct in Phase 0. |
| 12 | `Pageable` construction | By hand from `ApiProperties` | One source of truth for API config rather than Spring's built-in resolver. |
| 13 | Error codes | `ErrorCode` interface in `common`, enums per feature | Keeps `common` from importing features. |

**Ours, not from the spec:** `slug` on `organizations`. `PROJECT_CONTEXT.md` §2.1 gives *projects* a key (`TF`) but says nothing about an org identifier. Be ready to justify it.

---

## 1. What we covered

| § | Built | Key artifacts |
|---|---|---|
| **0.1** | Project skeleton, package-by-feature, git | `pom.xml` (Boot 3.5.16, Java 21), `common/{config,error,web,logging,persistence}`, `organization/` |
| **0.2** | Postgres via Docker Compose | `compose.yaml` (pinned image, named volume, healthcheck), `.env` + `.env.example` |
| **0.3** | Profiles & typed configuration | `application{,-dev,-prod,-test}.yml`, `ApiProperties` |
| **0.4** | Flyway & schema conventions | `V1__create_organizations.sql`, `global_id_seq`, named constraints |
| **0.5** | `BaseEntity` & JPA auditing | `BaseEntity`, `AuditingConfig`, `AuditAwareImpl`, `Organization`, `OrganizationRepository` |
| **0.6** | Web layer contracts | Controller / service / DTOs, `PageResponse`, `PageableFactory`, `PageQuery` |
| **0.7** | Global error handling | `GlobalExceptionHandler`, `ErrorCode`, `CommonErrorCode`, `OrganizationErrorCode`, `ApplicationException` + subtypes |
| **0.8** | Correlation ID & structured logging | `CorrelationIdFilter`, dev log pattern, prod ECS JSON |

**Working end to end:** `POST /api/v1/organizations` → 201 + `Location`, row persisted with audit columns · `GET /{id}` → 200/404 · `GET /` → paginated, capped, stable-sorted, **2 SQL queries** · every error an RFC 7807 `ProblemDetail` with a stable `code` and a `correlationId` that appears in the logs.

**Remaining in Phase 0:** §0.9 OpenAPI + Actuator (trim candidate) · §0.10 testing baseline · README.

---

## 2. Challenges we faced

### Environment and tooling

**Spring Initializr no longer serves Boot 3.** It rejects anything below 4.0.0 (`"compatibility range is >=4.0.0"`) in both the UI and the API — Boot 3.5's free support window ended mid-2026. *Resolution:* generate with Boot 4.1.1, then downgrade the pom. ⚠️ Not a one-line change — **Boot 4 renamed the starters**: `spring-boot-starter-webmvc` → `spring-boot-starter-web`, `spring-boot-starter-flyway` → no such thing (use `flyway-core`), and five modular `*-test` starters collapse into one `spring-boot-starter-test`.

**No JDK 21 installed** (only 25 and Corretto 8). *Resolution:* `brew install openjdk@21`, `JAVA_HOME` in `~/.zshrc`. Verified by class-file major version 65.

**Docker daemon not running** despite the CLI being present. *Resolution:* `open -a Docker`.

**Lombok silently generated nothing under JDK 25.** Discovered by accident when Maven ran without `JAVA_HOME` — every Lombok method reported "cannot find symbol", a wall of errors looking nothing like "Lombok didn't run". *Cause:* Lombok is discovered as an annotation processor **implicitly from the classpath**; JDK 21 warns about implicit annotation processing and **JDK 23+ disables it by default**. The build worked only because `JAVA_HOME` pointed at 21. *Resolution:* declare Lombok explicitly in `maven-compiler-plugin`'s `annotationProcessorPaths`. Now compiles on 21 **and** 25.

### Code that compiled, ran, and quietly did the wrong thing

**This is the theme of Phase 0.** Four separate bugs, none of which crashed:

| Bug | What actually happened |
|---|---|
| `@Max(100)` on a `String` | `@Max` is a *numeric* constraint, but Hibernate Validator ships a `CharSequence` validator that **parses the string as a number**. `"Acme Corp"` isn't one, so **every real organization name was rejected** — with the message *"must be less than or equal to 100"*. Fix: `@Size`. |
| `org.hibernate.sql` (lowercase) | Logback happily created a logger by that name and set it to DEBUG. Hibernate's logger is `org.hibernate.SQL`. Logger names are case-sensitive, so it **logged nothing**. |
| `insertable = false` on `updated_at` | The reasoning was sound ("it hasn't been updated yet"), but the column is `NOT NULL` with no default, so Hibernate omitted it from the INSERT and **the first write failed**. `ddl-auto=validate` passed — it checks that columns exist with compatible types, not nullability against the mapping. |
| `${DB_PASSWORD:taskflow_local_dev}` | After correctly removing hardcoded prod values, colon-defaults were added to *every* variable including the password — so a prod deploy with no config would have started successfully on localhost with the committed dev password. |

💡 **The lesson worth carrying:** none of these threw. They compiled, started, and produced plausible-looking behaviour. This is the entire argument for §0.10 — a `@WebMvcTest` asserting the exact validation response would have caught the first in seconds.

### Design problems that needed thinking

**`common` would have had to import a feature.** The exception advice lives in `common/error`; `DuplicateSlugException` lived in `organization/`. Importing it would have inverted the dependency rule set in §0.1 — and multiplied by twelve features, `common/error` ends up importing the whole app. *Resolution:* an `ErrorCode` **interface** in `common`, implemented by per-feature enums. Exception *types* stay generic (`ResourceConflictException`); the *codes* stay with their feature.

**"If I override `handleExceptionInternal`, how do I set a code for every exception?"** *Resolution:* recognise two different kinds of error. **Domain errors** carry their own `ErrorCode` because you decided what they mean. **Protocol errors** (405, 415, malformed JSON) are already classified by their HTTP status — there's no extra fact to encode. So the code is derived from the status for those, with two explicit overrides where it matters. You enumerate a handful of statuses, not twenty exception types.

**Check-then-insert is a race.** 30 concurrent POSTs with the same new slug produced 1 × 201, 25 × 409 from the service check, and **4 × 500** from the unique constraint — same logical error, two different status codes depending on timing. *Resolution:* handle `DataIntegrityViolationException` → 409. Deliberately **not** built: a constraint-name → error-code registry, which would have reintroduced the `common`→feature dependency for one mapping. The constraint name goes to the log and a response property instead.

**The race didn't reproduce at low concurrency** — six requests all got caught by the service check. Needed 30 before the window opened. ⚠️ Worth remembering: *"I couldn't reproduce it"* is not evidence a race doesn't exist.

**Client-supplied `sort` was a client-controlled 500.** `?sort=doesNotExist` → `PropertyReferenceException`. Beyond the wrong status, every entity field was sortable — and in Phase 5, `?sort=parent.title` would let a client force an unplanned join. *Resolution:* a per-endpoint whitelist.

**The correlation ID had nothing to correlate to.** The filter worked, but a *successful* request logged only Hibernate SQL lines — which are **dev-only**. In prod, a successful request produced zero log lines, and 4xx were logged at `DEBUG` (invisible at INFO). A user could quote an ID from a 404 and the logs had no record it happened. *Resolution:* one INFO completion line per request.

**Spring Data skips the count query** when you're on the first page and the content is smaller than the page size — so measuring with 1 row and `size=20` shows **one** query, not two, and looks broken. Measure with more rows than the page size.

### Process

**`git commit -a` swept unrelated files** — twice, pulling doc edits into unrelated commits. `git add <file>` when anything else might be dirty.

---

## 3. Production practices implemented, and what each prevents

### Schema & database

| Practice | What it prevents |
|---|---|
| Flyway migrations only; **never** `ddl-auto=update` | Schema drift with no review trail. `update` only ever *adds* — renames leave both columns, deletions leave the column forever. |
| `ddl-auto=validate` | Entity/migration drift. Add a field without a migration and **the app refuses to start** — in CI, before deploy. Verified deliberately. |
| `flyway.clean-disabled=true` | One misconfigured job dropping every object in the schema. |
| Migrations immutable, fix forward | Three environments each holding a different "V1". Enforced by checksums. |
| **Explicitly named constraints** (`uk_organizations_slug`) | Auto-generated names are unusable for error mapping. Demonstrated: the constraint name appears in the log when the race fires. |
| `timestamptz`, never `timestamp` | Timestamps silently meaning "whatever the server thought". Unfixable once you have a second timezone. |
| Deliberate `varchar` lengths + `CHECK` on slug format | Unbounded input reaching the database. The DB is the final guard when a bug bypasses the service. |
| Sequence `increment by 50` matched to `allocationSize = 50` | Duplicate-key violations under concurrency, appearing weeks later, far from the cause. |

### Configuration & secrets

| Practice | What it prevents |
|---|---|
| Typed `@ConfigurationProperties`, constructor-bound, `@Validated` | Property typos surfacing at 3am on an untested path. Fails at **startup**, in CI. |
| Cross-field invariant in the record's compact constructor | `defaultPageSize > maxPageSize` starting cleanly. Now load-bearing — `PageableFactory` skips a runtime check because startup guarantees it. |
| **No defaults for secrets** — `${VAR}`, never `${VAR:default}` | A prod deploy missing its config starting anyway with a placeholder password. Verified: prod profile with no env dies at startup. |
| Secrets from env vars; `.env` gitignored, `.env.example` committed | Credentials in git history. |
| **`.gitignore` rule added *before* the secret file existed** | Ordering is the defence — once committed, it's in history forever. |
| Config named for the consumer (`DB_*`, not `POSTGRES_*`) | An application config contract describing one developer's laptop. |
| Three profiles, prod with no Hibernate logging | Every SQL statement — and its bound parameters — written to production logs. |

### JPA & transactions

| Practice | What it prevents |
|---|---|
| `open-in-view=false` from day one | N+1 hidden inside JSON serialisation; a DB connection held for the whole request. |
| Explicit `@Transactional` in the service, `readOnly = true` on queries | Ambiguous boundaries; wasted dirty-checking and snapshot retention. |
| Entity → DTO mapping **inside** the transaction | `LazyInitializationException` the moment an association is added (Phase 5). |
| No `equals`/`hashCode` on entities, deliberately | An entity lost inside a `HashSet` after persist, when the generated ID changes its hash. |
| Audit fields have no public setters | Application code overwriting creation metadata. |
| `updatable = false` on created fields | An update rewriting who created the row. |

### API & web layer

| Practice | What it prevents |
|---|---|
| Package-by-feature; no feature imports another | Four packages edited for every change; invisible coupling. Already paid off — it forced the `ErrorCode` design. |
| DTOs as records; entities never in controller signatures | JSON coupled to schema; over-exposure (a password hash in Phase 1); clients supplying `id` and audit fields. |
| Controllers do HTTP only | Business rules unreachable from anywhere but a web request, and untestable without one. |
| Own `PageResponse`, never `PageImpl` | A JSON contract that is a serialisation of a Spring internal class. |
| Page size capped from config | `?size=99999` loading the table into memory. |
| **Stable sort with a tiebreaker** | Page 2 repeating or skipping rows — silent, intermittent, unreproducible on demand. |
| Per-endpoint **sort whitelist** | Client-controlled 500s, and (Phase 5) a client forcing unplanned joins. |
| Three-layer validation | Boundary catches malformed input cheaply · service enforces rules needing DB state · **constraint catches the race between check and insert**. |
| `201` + `Location` on create | Clients constructing resource URLs themselves. |

### Errors

| Practice | What it prevents |
|---|---|
| One RFC 7807 `ProblemDetail` contract | Every endpoint inventing its own error shape. |
| **One funnel** (`handleExceptionInternal`) | The same stamping logic duplicated across handlers. Adding `correlationId` in §0.8 was one line. |
| `ErrorCode` enum as a public contract | Clients string-matching on prose messages. |
| Structured properties (`slug`, `id`, `property`) | Machine-readable data buried in a sentence. |
| Generic 500 body; full detail logged server-side | Exception messages leaking schema, file paths, library versions, SQL. |
| `include-stacktrace` / `include-message` = `never` | Boot's fallback error path leaking what your handler carefully doesn't. |
| Log levels by severity — `debug` 4xx, `warn` constraints, `error` unexpected | Client typos filling the error log and drowning the one real failure. |

### Logging & observability

| Practice | What it prevents |
|---|---|
| Correlation ID as a **filter**, highest precedence | An interceptor wouldn't run for 401/403 — exactly the responses you need to trace. |
| `OncePerRequestFilter` | Two IDs for one request on internal `FORWARD`/`ERROR` dispatch. |
| **`MDC.remove` in `finally`** | A pooled thread stamping one request's ID onto the next request's logs — debugging with confidently wrong evidence. |
| Header validated (whitelist + 64-char cap) | Log injection via newlines (forged log lines) and log flooding. |
| Response header set **before** `doFilter` | Headers silently lost once the response commits. |
| One completion line per request, at INFO | A correlation ID with nothing to correlate to. |
| `getRequestURI()` — no query string | `?token=...` written to access logs in plaintext. |
| `System.nanoTime()` for duration | Negative durations when the wall clock steps. |
| Parameterised SLF4J | String built even when the level is disabled; broken structured-log field extraction. |
| Structured JSON (ECS) in prod | MDC values buried in message text instead of being queryable fields. |

### Build & infrastructure

| Practice | What it prevents |
|---|---|
| Pinned image tag (`postgres:17-alpine`) | A silent major upgrade making the data directory unreadable. |
| Named volume | Data lost on `docker compose down`. |
| Healthcheck with `-U`/`-d` and `start_period` | Connecting to a bootstrapping server that's about to restart. |
| `annotationProcessorPaths` for Lombok | A build that only works on one JDK, failing with "cannot find symbol". |
| Small, per-feature commits | Reviews and bisects that can't isolate a change. |

---

## 4. Learning at each stage

**§0.1 — Skeleton.** Auto-configuration is `@Conditional`-driven, not magic; `@ConditionalOnMissingBean` is why your `@Bean` wins. `@SpringBootApplication` implies `@ComponentScan` from **its own package downward** — move the main class and beans silently vanish. Package-by-feature makes cross-feature dependencies visible instead of accidental.

**§0.2 — Postgres.** A `.env` file beside `compose.yaml` is read **by Compose** for `${VAR}` substitution; `env_file:` passes variables **into the container** — different mechanisms. `CMD-SHELL` runs through a shell so `${VAR}` expands; `CMD` doesn't. Postgres only initialises on an **empty data directory**, so changing the password later does nothing until `down -v`.

**§0.3 — Configuration.** Profile files *merge* — the base always loads, the profile file overrides keys. Relaxed binding maps `default-page-size` → `defaultPageSize` and `SPRING_DATASOURCE_URL` → `spring.datasource.url`. Environment variables sit **above** YAML in the property source order, which is what makes 12-factor config work. Field-level Bean Validation cannot express a relationship between two fields — that needs a compact constructor or a class-level constraint.
*Deliberately broken:* `max-page-size: 0` → startup failure naming the property, value and constraint.

**§0.4 — Flyway.** Migrations are files applied once, in order, recorded with a **checksum** — which is what makes them immutable and why you roll forward, never back (undo is a paid feature). Flyway runs **before** the `EntityManagerFactory`; Boot makes the EMF depend on the Flyway bean, which is visible in the stack trace. That ordering is what turns `validate` into a real guard. One underscore instead of two and the file is silently ignored.
In Postgres: `text`, `varchar` and `varchar(n)` are the same type — `(n)` is only a constraint. Increasing a length is instant; decreasing requires a table scan.

**§0.5 — Entities & auditing.** `@MappedSuperclass` shares mapping with no table and no polymorphic queries — unlike `@Embeddable` (a value object) or `@Inheritance` (a real type hierarchy). Auditing needs `@EntityListeners` or the annotations are inert. `@EnableJpaAuditing` belongs on its own `@Configuration`, not the main class, or it activates in slice tests with no JPA.
`timestamptz` **does not store a zone** — it stores a UTC instant and discards the offset, which is why `Instant` is the correct Java type and `OffsetDateTime` promises something the column can't keep. ⚠️ `Instant` holds nanoseconds, `timestamptz` microseconds — round-trips truncate, so exact-equality assertions on timestamps can fail invisibly (this will matter in §0.10).
Some JPA annotations affect runtime (`sequenceName`, `allocationSize`, `insertable`, `updatable`); others are DDL-generation only and **inert under `validate`** (`initialValue`, `unique`, `length`).

**§0.6 — Web contracts.** `@Transactional` works by **proxy** — a call from within the same class bypasses it entirely and silently runs with no transaction. `JpaRepository` already has `findAll(Pageable)`; `Page.map()` converts the content type while preserving metadata **without another query**. Sort properties are *entity field names*, not column names. Offset pagination degrades with depth — `OFFSET 100000` makes Postgres produce and discard 100,000 rows; keyset pagination is the answer at scale.
Sequence gaps (ids 202–206, not 1–5) are the pooled allocator working: each restart abandons the rest of its block. Sequences guarantee uniqueness, never contiguity.

**§0.7 — Errors.** `ResponseEntityExceptionHandler` already handles the MVC exceptions and funnels them all through `handleExceptionInternal` — call `super` first and **enrich** the `ProblemDetail` it produced rather than building a fresh one, or you discard the framework's title, detail and message-source resolution. `org.hibernate.exception.ConstraintViolationException` and `jakarta.validation.ConstraintViolationException` share a name and are unrelated types.
⚠️ Security exceptions are thrown **inside the filter chain**, before `DispatcherServlet` — `@RestControllerAdvice` will never see them. Phase 2 needs an `AuthenticationEntryPoint` and `AccessDeniedHandler`. Same reason exceptions thrown from a filter bypass the advice.

**§0.8 — Observability.** MDC is a `ThreadLocal` map the logging framework reads via `%X{key}`. 📌 The same ThreadLocal-plus-pooled-thread hazard returns in **Phase 3** (tenant context) and **Phase 9** (`@Async` not inheriting context) — learn it once, it pays three times.
Boot's default console pattern contains an empty `${LOG_CORRELATION_PATTERN:-}` slot, fillable with `logging.pattern.correlation` — no need to rewrite the pattern. Boot 3.4+ has native structured logging (`logging.structured.format.console=ecs`), so no `logstash-logback-encoder`. In JSON output MDC entries become **top-level queryable fields**.
Production systems usually use Micrometer Tracing / OpenTelemetry with W3C `traceparent` rather than hand-rolling this; the hand-rolled version is for learning the mechanism.

---

## 5. Interview questions

### Answerable now, with the shape of the answer

1. **How does Boot decide which auto-configurations apply, and how do you override one?** Conditional annotations on classes listed in `AutoConfiguration.imports`; `@ConditionalOnMissingBean` means your own `@Bean` wins. `--debug` prints the match report.
2. **Why Flyway over `ddl-auto=update`?** Versioned and reviewable in git · no data-destructive guesses and no data migrations from Hibernate · `update` only ever adds, so prod diverges from your code permanently.
3. **How do you manage schema changes across environments?** Versioned migrations in source control, immutability enforced by checksums, roll forward not back, `validate` as the drift guard.
4. **How do you stop someone forgetting a migration?** Not discipline — `ddl-auto=validate` makes the app refuse to start, in CI, before deploy.
5. **What does `open-in-view=false` change?** Lazy access outside a transaction fails loudly instead of silently firing queries during serialisation and holding a connection for the whole request.
6. **Why `Instant` and not `LocalDateTime` for a timestamp column?** `timestamptz` stores an absolute UTC instant with no zone; `Instant` means exactly that. `LocalDateTime` has no zone; `OffsetDateTime` claims to preserve one the column discards.
7. **Why is `@Data` dangerous on a JPA entity?** Generated `equals`/`hashCode` break the hash contract for generated IDs, and `@ToString` walks lazy associations — a log statement causing a query storm or an exception.
8. **Why is `IDENTITY` a problematic ID strategy at scale?** A DB round-trip per persist, and it silently disables JDBC batch inserts.
9. **Does a unique column need an index?** No — Postgres implements `UNIQUE` by creating a unique B-tree index, named after the constraint.
10. **Why are there gaps in my IDs?** Pooled sequence allocation; each restart abandons the rest of its block. Sequences guarantee uniqueness, not contiguity.
11. **How do you see the SQL Hibernate generates and the parameters it binds?** `org.hibernate.SQL` at DEBUG, `org.hibernate.orm.jdbc.bind` at TRACE — not `show-sql`, which bypasses the logging framework entirely.
12. **How do you make sure a misconfigured deploy fails loudly?** No defaults for secrets, and typed `@ConfigurationProperties` validated at startup.
13. **How does Spring know which classes to scan?** From the `@SpringBootApplication` class's own package downward.
14. **How would you debug a slow query in production?** Database-side metrics and slow-query logs — not DEBUG logging in the app, which costs throughput and risks PII.
15. **Filter or interceptor?** A filter, ordered early — an interceptor runs inside `DispatcherServlet` after handler mapping, so it never runs when security rejects a request, which is exactly what you need to trace.
16. **Walk me through your API error contract.** RFC 7807 `ProblemDetail`, a stable `code` enum, structured properties, a `correlationId`, one funnel so every error — domain and framework — has the same shape.
17. **Where could a 500 leak information, and how did you stop it?** Exception messages carry schema, paths, library versions and SQL. Generic body to the client, full stack trace to the logs, `include-stacktrace`/`include-message` never.
18. **Why extend `ResponseEntityExceptionHandler` rather than write a plain advice?** The MVC exceptions are already handled, and there's a single `handleExceptionInternal` funnel to stamp shared fields on every response.
19. **How do you handle a race condition?** Not a lock — the service check handles the common case cheaply, the database constraint guarantees correctness, and both map to the same 409. Demonstrated under 30 concurrent requests.
20. **How do you stop a client controlling your query plan?** Whitelist sortable fields per endpoint; an unvalidated `sort` parameter is client-controlled SQL ordering and, with associations, client-controlled joins.
21. **A user reports a failed request at 14:32 — find it.** Correlation ID generated in a filter, put in MDC, returned in the response header and in the error body, present on every log line for that request, and a queryable field in prod's JSON logs.
22. **How would you paginate a million rows?** Offset pagination degrades with depth because the database produces and discards the skipped rows; keyset/seek pagination is the answer.
23. **What broke when you upgraded the JDK?** Lombok stopped generating — implicit annotation processing is disabled by default in JDK 23+. Fixed by declaring it in `annotationProcessorPaths`.
24. **Why three layers of validation?** The boundary catches malformed input cheaply, the service enforces rules needing DB state, and the constraint catches the race between check and insert.
25. **What causes unstable pagination?** Sorting without a deterministic tiebreaker — rows sharing a sort value have no defined order, so pages repeat or skip.

### Not answerable yet

- **Why not H2 in tests?** — §0.10
- **Why is a Spring test suite slow / what is context caching?** — §0.10
- **How do you implement `equals`/`hashCode` on a JPA entity?** — Phase 5 (you know the naive answer is wrong and why)
- **How would you do distributed tracing?** — partial; Micrometer Tracing / OpenTelemetry named but not used

---

## 6. Commands

```bash
# Database
docker compose up -d && docker compose ps          # STATUS must read healthy
docker compose exec postgres psql -U taskflow -d taskflow
docker compose down                                 # stop, keep data
docker compose down -v                              # DROP DATA — the reset button

# Inside psql
\dt   \ds   \d organizations
select version, description, success from flyway_schema_history;

# App
./mvnw clean compile
./mvnw spring-boot:run
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod   # must FAIL: missing DB_* vars
./mvnw spring-boot:run -Dspring-boot.run.arguments=--debug   # auto-configuration report
# NOT `./mvnw spring-boot:run --debug`: Maven takes --debug as its OWN flag (= -X) and never passes it to the app

# Read Boot's own config when docs are thin
unzip -p ~/.m2/repository/org/springframework/boot/spring-boot/3.5.16/spring-boot-3.5.16.jar \
  org/springframework/boot/logging/logback/defaults.xml | grep CONSOLE_LOG_PATTERN
```

---

## 7. Deliberate failures — the strongest material

Each created on purpose to see the error. These are experience, not theory.

| Broke | Result |
|---|---|
| `max-page-size: 0` | Startup fails, naming property, value and violated constraint |
| `default-page-size > max-page-size` | Startup fails from the record's compact constructor |
| `prod` profile with no env vars | Startup fails — unresolved placeholder |
| Entity field with no migration | Startup fails — `validate` catches the drift |
| `insertable = false` on a `NOT NULL` column | Startup **passes**; the first INSERT fails |
| 30 concurrent POSTs, same slug | 1 × 201, 25 × 409 (service), 4 × 409 (constraint) — **zero 500s** after the fix |

The last two are the best pair: one shows what schema validation guarantees, the other shows its limit.

---

## 8. Carried debt

- **§0.9 OpenAPI — deferred by choice (2026-09-23)** to learn it first. Use springdoc **2.9.1** (3.x is Boot 4). The four verified fixes are in `PHASE_0_REQUIREMENTS.md` §0.9. Until then, springdoc must **not** be on the classpath — verified that it serves Swagger UI and the spec under the `prod` profile.
- `/actuator/info` works **only when Maven builds the app** — IntelliJ's own builder skips Maven plugin goals. Fix: Maven tool window → Lifecycle → `generate-resources` → *Execute Before Build*. Use `management.info.git.mode: simple` — `full` exposed a personal email, the build host's internal IP and the remote URL on an unauthenticated endpoint.
- **README** — not written. Holds the API conventions, DB naming conventions, and the prod `DB_*` variable list. Outstanding since §0.1.
- **`--debug` auto-configuration report** — not yet read. Two minutes, and it's interview question #1.
- `@NoArgsConstructor` still public on `Organization` (`protected` is enough for JPA).
- Unknown paths leak `"No static resource api/v1/nope."`; type-conversion messages leak `java.lang.Integer`.
- `CorrelationIdFilter` now does two jobs; split if it grows.
- Sort whitelist excludes `name` and `slug` — the two fields a user would most want to sort by.
- Multiple validation errors per field are returned ungrouped; the `@Pattern` message echoes the raw regex.
- Java `@Size(min = 3)` on slug is stricter than the DB `CHECK`, which accepts one character.
- **§2.1 ↔ §5 reconciliation:** the security-action audit log has no home in the week plan.
