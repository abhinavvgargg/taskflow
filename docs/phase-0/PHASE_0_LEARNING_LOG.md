# Phase 0 — Learning Log

> Everything covered while building §0.1–§0.5 (2026-09-21). Concepts, traps, decisions and answers.
> Companion to `PHASE_0_REQUIREMENTS.md` (what to build) and `PROJECT_CONTEXT.md` (the plan).

---

## 1. Decisions made, and why

| # | Decision | Chosen | The one-line justification |
|---|---|---|---|
| 1 | Build tool | Maven 3.9.16 | Standard in enterprise Spring shops; what a take-home hands you. |
| 2 | Boot version | 3.5.16 | Matches the tutorials and the job market. Initializr no longer offers it — generate with Boot 4, downgrade the pom. |
| 3 | Lombok | Full, **never on entities** | `@Slf4j` + `@RequiredArgsConstructor` only. |
| 4 | Primary key | `bigint` + sequence, `global_id_seq`, `increment by 50` | 8-byte FKs, batch-insert friendly, perfect index locality; adding an opaque public ID later is cheap, changing PK type is not. |
| 5 | Phase 0 slice | `organizations` | A real table you keep; zero throwaway code. |
| 6 | Java | 21 (Homebrew `openjdk@21`) | Plan and resume say 21; avoids stacking Lombok + Boot + JDK bleeding edges. |
| 7 | `id` location | In `BaseEntity`, one shared sequence | Less repetition. Trade-off: all tables share a number space. Per-table sequences would need `id` in each entity. |
| 8 | Config format | YAML | Nesting across three profiles; `.properties` repeats the prefix every line. |

**Still open:** whether audit `created_by` becomes an FK to `users` (Phase 1), and whether organizations get an opaque public ID for URLs.

---

## 2. Concepts — grouped the way interviews ask

### Spring Boot internals

**Auto-configuration.** Boot ships hundreds of `@AutoConfiguration` classes listed in `META-INF/spring/…AutoConfiguration.imports`. Each activates only if its conditions hold — `@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`. *Why it matters:* you must be able to say why a bean exists. "Boot magic" is a bad answer; "there's a conditional on missing bean, so my `@Bean` wins" is a good one.
→ `--debug` prints the report: **Positive matches / Negative matches / Exclusions.**

**We watched this happen.** `DataSourceAutoConfiguration` refused to produce a `DataSource` ("Failed to determine a suitable driver class") because no URL was configured. Once §0.3 supplied one, the same class produced the bean. Auto-configuration is conditional, not magical.

**`FailureAnalyzer`.** Boot turns common startup failures into a readable `APPLICATION FAILED TO START` block with Description / Reason / Action. `DataSourceBeanCreationFailureAnalyzer` is the one we hit. Test failures print the raw trace instead, which is why the same error looked like 200 lines in one place and 3 lines in another.

**Reading Spring stack traces.** The first exception is the most generic and least useful (`Failed to load ApplicationContext`). **Each `Caused by:` is one layer closer. The last one is the real cause.** Scroll to the bottom first.

**Component scanning.** `@SpringBootApplication` includes `@ComponentScan` with no arguments = "scan my own package and below." The main class's package is the scan root — move it into a subpackage and Spring silently stops finding your beans.

**Bean resolution.** With exactly one bean of a type, Spring Data resolves `AuditorAware` by type and you can drop `auditorAwareRef` entirely. Fewer strings to typo. Two beans of the same type → `NoUniqueBeanDefinitionException` the moment something injects by type.

### Configuration

**Typed config beats `@Value`.** `@ConfigurationProperties` + constructor-bound record + `@Validated` + Bean Validation constraints = one object, validated **at startup**, in CI. `@Value` scattered across classes gives you typos that surface at 3am on the one path nobody tested.

**Relaxed binding.** `default-page-size` in YAML → `defaultPageSize` in Java. Boot tries kebab-case, camelCase, underscores, uppercase. This is also why `SPRING_DATASOURCE_PASSWORD` as an env var works.

**Property source ordering.** Environment variables sit **above** YAML files. That's what makes 12-factor config work: prod supplies secrets from the environment and they override anything in a file.

**`${VAR}` vs `${VAR:default}`.** One character. The first fails startup if the variable is missing. The second silently substitutes a placeholder — which is how a dev password reaches production. **No defaults for secrets, ever.**

**Profile files merge, they don't replace.** `application.yml` always loads; `application-{profile}.yml` overrides individual keys on top. Put shared settings in the base file.

**Cross-field validation is beyond `@Min`/`@Max`.** Field-level constraints validate one field in isolation. `defaultPageSize <= maxPageSize` is a *relationship* and is invisible to them. Two answers: a **compact constructor** on the record (what we used), or a **class-level `@ConstraintValidator`** (composable, reportable through the normal validation pipeline — the answer for request DTOs later).

**Name config for the consumer, not the producer.** `POSTGRES_USER` is the *Postgres container's* API — its entrypoint reads it to create a superuser. Your app needs "what user do I connect as," which is a different job with different security properties. `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` says "this app needs a database" and stays honest whether that's Docker, RDS or a connection pooler.

### JPA & Hibernate

**`open-in-view=false`.** Boot defaults it to `true`, holding the Hibernate session open through view rendering. Off because: it hides N+1 (they fire during JSON serialisation where no test looks), holds a DB connection for the whole request, and defers `LazyInitializationException` until the worst moment.

**`ddl-auto=validate`** checks entities against the real schema at startup. Add a field without a migration → **the app refuses to start**. Guarantees beat discipline.
→ But it only checks that tables and columns **exist with compatible types**. It does *not* check nullability against your mapping, unique constraints, indexes, or `insertable`/`updatable`. A whole class of mapping bugs only appears on the first write.

**`show-sql=true` is the wrong tool.** It writes straight to stdout, bypassing your logging framework — no levels, no formatting, no correlation ID. Use loggers:

| Logger | Level | Gives you |
|---|---|---|
| `org.hibernate.SQL` | `DEBUG` | the statements (**uppercase SQL** — case-sensitive) |
| `org.hibernate.orm.jdbc.bind` | `TRACE` | bound parameter values (**Hibernate 6** name) |

**`@MappedSuperclass` vs `@Embeddable` vs `@Inheritance`.** The first shares *mapping* with no table and no polymorphic queries. The second groups columns into a reusable value object. The third creates a real, queryable type hierarchy with table-strategy cost.

**JPA auditing.** `@EnableJpaAuditing` + `@EntityListeners(AuditingEntityListener.class)` on the entity + an `AuditorAware` bean. Without the listener the annotations are **inert** — fields stay null and your `NOT NULL` column rejects the insert, with an error pointing at the database rather than the missing annotation.
→ Auditing sets **both** created and last-modified on insert. A fresh row legitimately has `updated_at == created_at`.
→ Put `@EnableJpaAuditing` on its own `@Configuration`, not the main class — on the main class it activates in every slice test, and `@WebMvcTest` (no JPA) fails looking for an `AuditorAware`.

**`updatable = false` vs `insertable = false`.** The first is correct on created-fields: nothing can rewrite creation metadata. The second **omits the column from the INSERT** — fatal against a `NOT NULL` column with no default.

**Which JPA annotations actually do anything at runtime:**

| Runtime behaviour | DDL-generation only (inert under `validate`) |
|---|---|
| `sequenceName`, `allocationSize` | `initialValue` |
| `insertable`, `updatable` | `unique` |
| `nullable` | `columnDefinition`, `length` |

**ID generation.** `IDENTITY` forces a DB roundtrip per `persist` and **silently disables JDBC batch inserts**. A sequence with a pooled allocation asks the database once per *N* inserts and hands out the rest from memory.
→ ⚠️ **`allocationSize` must equal the sequence's `INCREMENT BY`.** Ours: both 50.

**`equals`/`hashCode` on entities — deliberately absent.** A new entity has `id == null`. Add it to a `HashSet`, persist it, the ID appears, its hash code changes while it's in the set, and the set can no longer find it. Phase 5 covers the correct patterns.

### PostgreSQL

**`text`, `varchar`, `varchar(n)` are the same type** — identical storage, identical performance. `(n)` is purely a length constraint. (`char(n)` blank-pads and is almost always wrong.) So the question is never "which is faster," it's "do I want a limit, and what should it be?"
→ Increasing a `varchar` length later is metadata-only and instant. **Decreasing requires a full table scan.** Err generous.

**`timestamptz` does not store a time zone.** Despite the name, it stores a UTC instant — the input offset is used to convert, then discarded, and reads render in the session's zone.
→ So `Instant` is the correct Java type. `OffsetDateTime` promises to preserve an offset the column throws away. `LocalDateTime` has no zone at all and is silently wrong across DST.
→ ⚠️ `Instant` holds **nanoseconds**, `timestamptz` holds **microseconds**. A save-then-reload round-trip truncates, so exact-equality assertions on timestamps can fail on an invisible difference.

**A `UNIQUE` constraint *is* an index.** Postgres implements it by creating a unique B-tree, named after the constraint. A second index on the same column is pure duplicate write cost. Being a B-tree, it also serves `ORDER BY` and prefix lookups for free.

**Name every constraint explicitly** — `pk_ uk_ ck_ fk_ ix_`. The payoff arrives in §0.7: when Postgres throws a unique violation, the **constraint name is the only reliable thing in the exception**. `uk_organizations_slug` maps cleanly to error code `ORGANIZATION_SLUG_TAKEN`. Auto-generated names don't.

**Postgres regex uses POSIX ARE** via the `~` operator, and supports non-capturing groups. `^[a-z0-9-]+$` looks right but accepts `---`, `-acme` and `acme--corp`; `^[a-z0-9]+(?:-[a-z0-9]+)*$` expresses "words separated by single hyphens."

### Flyway

**The problem it solves.** Your Java is versioned in git; your schema isn't. Without migrations, production has a schema that exists only because of `ALTER`s people typed over eighteen months, and nobody can reproduce it.

**Why not `ddl-auto=update`:** it only ever *adds* (rename a column and you get both); it can't migrate data; there's no diff and no review; and it's non-deterministic across Hibernate versions, so dev and prod drift silently.

**How it works.** Files in `db/migration`, named `V<n>__<description>.sql`. On startup Flyway creates `flyway_schema_history` if absent, scans the classpath, compares against what's recorded, applies pending migrations in version order (each in a transaction), and records each with a **checksum**.

**Checksums make migrations immutable.** Edit an applied migration and Flyway refuses to start — because your DB, your teammate's DB, and a fresh DB would otherwise hold three different schemas all calling themselves "V1." **Fix forward with a new version.**
→ There is **no free rollback**. `undo` is a paid feature. You roll forward with a reversing migration. This is also why migrations should be small and boring.

**Flyway runs before the `EntityManagerFactory`.** Boot makes the EMF depend on the Flyway bean — visible in the stack trace we hit: *"Failed to initialize dependency 'flyway' of LoadTimeWeaverAware bean 'entityManagerFactory'"*. That ordering is what makes `validate` a real guard: **Flyway builds, Hibernate checks.**

**Types:** `V` runs once in order (95% of what you write) · `R` re-runs when its checksum changes (views, functions) · `U` is undo, a paid feature.

### Docker & Compose

**`.env` vs `env_file:` — two different mechanisms.** A `.env` file next to `../../compose.yaml` is read **by Compose itself** to substitute `${VAR}` *in the compose file*. `env_file:` passes variables **into the container**. We use the first, so the compose file documents which variables exist.

**`CMD` vs `CMD-SHELL` in a healthcheck.** `CMD` execs directly with no shell, so `${POSTGRES_USER}` never expands. `CMD-SHELL` runs through `sh` inside the container.

**Healthcheck must name the user and database.** On first run Postgres starts a temporary server, initialises, then **shuts down and restarts**. A bare `pg_isready` can report healthy during that window, so your app connects to a server about to vanish. `start_period` gives the bootstrap room before failures count.

**Named volumes** are managed by Docker, survive `docker compose down`, and avoid macOS's slow bind-mount layer.

**No `version:` key.** Required in Compose v1, obsolete in v2. A tutorial starting with `version: "3.8"` is old.

### Security hygiene

**Add the `../../.gitignore` rule *before* the secret file exists.** Once committed, it's in history forever — removal needs a rewrite, and if pushed, the credential must be treated as leaked and rotated. Ordering is the whole defence.

**Three layers of validation, each catching what the others can't:** Bean Validation at the boundary catches malformed input cheaply · the service enforces rules needing DB state · the **database constraint catches the race between check and insert**.

---

## 3. Traps — quick reference

| ⚠️ Trap | What happens | Status |
|---|---|---|
| `ddl-auto=update` | Schema drifts silently; never drops or narrows | Avoided |
| Main class not in base package | `@ComponentScan` root moves; beans silently vanish | Avoided |
| Single `_` in a Flyway filename | File **silently ignored**, table never created | Known |
| Editing an applied migration | Checksum mismatch; Flyway refuses to start | Known |
| Two devs both create `V2` | Version collision on merge | Known |
| `flyway.clean` enabled | Drops every object in the schema | Disabled |
| `postgres:latest` | Silent major upgrade makes the data dir unreadable | Pinned to 17 |
| `pg_isready` without `-U`/`-d` | Reports healthy mid-bootstrap | Avoided |
| Changing `POSTGRES_PASSWORD` after first run | **Nothing happens** — init only runs on an empty data dir. Fix: `down -v` | Known |
| `.env` committed | Credential in history forever | Avoided |
| `${SECRET:default}` | Prod silently starts with a placeholder password | **Hit and fixed** |
| `spring.profiles.active` in the committed base file | A deploy that forgets the override runs dev config | Accepted, documented |
| `org.hibernate.sql` (lowercase) | Logger created, **logs nothing** — silent no-op | **Hit and fixed** |
| Hibernate 5 binder logger name on Hibernate 6 | Same silent no-op | Avoided |
| `@Data`/`@ToString` on an entity | `@ToString` walks lazy associations → `LazyInitializationException` or N+1 **from a log statement** | Banned |
| `@Builder` on an entity | Removes the no-arg constructor JPA requires | Banned |
| `equals`/`hashCode` with a generated ID | Entity lost inside a `HashSet` after persist | Deferred to Phase 5 |
| `@Table` name — class is singular, table is plural | `validate` fails at startup | **Hit and fixed** |
| `insertable = false` on a `NOT NULL` column | `validate` passes; the **first insert** fails | **Hit and fixed** |
| Two beans with near-identical names | Both exist; one is dead code; ambiguity later | **Hit and fixed** |
| `LocalDateTime` for `timestamptz` | Zone lost, wrong across DST | Avoided |
| `Instant` vs `timestamptz` precision | Round-trip truncates ns → µs; equality assertions fail | Known, bites in §0.10 |
| Both `application.properties` and `.yml` present | Both load, `.properties` wins | Avoided |
| `git commit -a` | Sweeps every tracked modified file, not just yours | **Hit twice** |
| Returning `Page`/`PageImpl` from a controller | Unstable JSON contract | Coming in §0.6 |
| Sorting without a tiebreaker | Page 2 repeats or skips rows | Coming in §0.6 |

---

## 4. Interview questions you can answer *now*

1. **How does Boot decide which auto-configurations apply, and how do you override one?** Conditional annotations on classes listed in `AutoConfiguration.imports`; `@ConditionalOnMissingBean` means your own `@Bean` wins. `--debug` prints the match report.
2. **Why Flyway over `ddl-auto=update`?** Three reasons: versioned and reviewable in git; no data-destructive guesses and no data migrations from Hibernate; `update` only ever adds, so prod diverges from your code forever.
3. **How do you manage schema changes across environments?** Versioned migrations in source control, immutability enforced by checksums, roll forward not back, `validate` as the drift guard.
4. **How do you stop someone forgetting a migration?** You don't rely on discipline — `ddl-auto=validate` makes the application refuse to start, in CI, before deploy.
5. **What does `open-in-view=false` change?** Lazy access outside a transaction now fails loudly instead of silently firing queries during serialisation and holding a connection for the whole request.
6. **Why `Instant` and not `LocalDateTime` for a timestamp column?** `timestamptz` stores an absolute UTC instant with no zone; `Instant` means exactly that. `LocalDateTime` has no zone; `OffsetDateTime` claims to preserve one the column discards.
7. **Why is `@Data` dangerous on a JPA entity?** Generated `equals`/`hashCode` over all fields breaks the hash contract for generated IDs, and `@ToString` walks lazy associations — a log statement causing a query storm or an exception.
8. **How do you implement `equals`/`hashCode` on a JPA entity?** (Know that the naive answer is wrong and why — full answer in Phase 5.)
9. **Why is `IDENTITY` a problematic ID strategy at scale?** DB roundtrip per persist, and it silently disables JDBC batch inserts.
10. **Does a unique column need an index?** No — Postgres implements `UNIQUE` by creating a unique B-tree index.
11. **How do you see the SQL Hibernate generates and the parameters it binds?** `org.hibernate.SQL` at DEBUG and `org.hibernate.orm.jdbc.bind` at TRACE — not `show-sql`, which bypasses your logging framework.
12. **How do you make sure a misconfigured deploy fails loudly?** No defaults for secrets (`${VAR}` not `${VAR:default}`), and typed `@ConfigurationProperties` validated at startup.
13. **How does Spring know which classes to scan?** From the `@SpringBootApplication` class's own package downward.
14. **How would you debug a slow query in production?** Database-side metrics and slow-query logs — not DEBUG logging in the app, which costs throughput and risks PII in the logs.

**Not yet answerable — coming up:** filter vs interceptor (§0.8) · the API error contract (§0.7) · why not H2 (§0.10) · context caching (§0.10) · unstable pagination (§0.6).

---

## 5. Commands cheat-sheet

```bash
# Database
docker compose up -d && docker compose ps          # start, check STATUS = healthy
docker compose logs -f postgres
docker compose exec postgres psql -U taskflow -d taskflow
docker compose down                                 # stop, keep data
docker compose down -v                              # stop, DROP DATA — the reset button

# Inside psql
\dt                 # tables
\ds                 # sequences
\d organizations    # one table: columns, indexes, constraints

# Flyway state
select version, description, success, execution_time from flyway_schema_history;

# App
./mvnw clean compile
./mvnw spring-boot:run
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod   # must FAIL: missing DB_* vars
./mvnw spring-boot:run --debug                            # auto-configuration report
```

---

## 6. Deliberate failures worth repeating

Each of these was created on purpose to see the error. They are the strongest interview material because they're experience, not theory.

| Broke | Result |
|---|---|
| `max-page-size: 0` | Startup fails naming the property, value and violated constraint |
| `default-page-size > max-page-size` | Startup fails from the record's compact constructor |
| Ran the `prod` profile with no env vars | Startup fails — unresolved placeholder |
| Added an entity field with no migration | Startup fails — `validate` catches the drift |
| `insertable = false` on a `NOT NULL` column | Startup **passes**; the first INSERT fails. *What `validate` cannot catch.* |

That last pair is the best story in the set: one shows what schema validation guarantees, the other shows its limit.

---

## 7. Where things stand

**Built:** §0.1 skeleton · §0.2 Postgres in Docker · §0.3 profiles + typed config · §0.4 Flyway + `V1` · §0.5 `BaseEntity` + auditing + `Organization`

**Left:** §0.6 web contracts and the first insert · §0.7 error handling · §0.8 correlation ID + logging · §0.9 OpenAPI + Actuator (**trim candidate**) · §0.10 testing baseline

**Carried debt:** README not written (holds the API and DB naming conventions) · `--debug` auto-config report not yet read · `@NoArgsConstructor` still public on `Organization` · `created_by varchar(50)` may be too short if Phase 1 audits by email.
