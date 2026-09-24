# Phase 1 — Revision & Learning Log

> Updated after every sub-phase. **Last updated 2026-09-25, covering §1.1–§1.2** (filter chain · users, passwords, principal, auditor).
> Companions: `PHASE_1_REQUIREMENTS.md` (what to build) · `SECURITY_TESTING_GUIDE.md` (how the rules are tested) · `../phase-0/PHASE_0_LEARNING_LOG.md` (the foundations this builds on).

---

## 0. Decisions on record

| # | Decision | Chosen | The one-line justification |
|---|---|---|---|
| 1 | Auth before JWT exists | **HTTP Basic, stateless**, plus an explicit `POST /auth/login` | Lets `/me` be built and tested now; the login endpoint is the seam Phase 2 extends to issue tokens. Basic is deleted in Phase 2. |
| 2 | Package layout | **`common/security` + `user`** | The principal type must live in `common`, because `AuditAwareImpl` reads it and `common` never imports a feature. Same reasoning as the `ErrorCode` interface. |
| 3 | Login identifier | **Email**; the email-or-username approach studied, not built | A separate username still exists for `created_by` (Phase 0 #9) and `@mentions` (Phase 9). Usernames forbid `@` so the two namespaces can't overlap. |
| 4 | Principal type | **A separate class** (`TaskflowPrincipal`), not the entity. Built as a plain class rather than a record. | It lives in the `SecurityContext` outside any transaction; an entity there is a detached, stale row with lazy associations waiting to throw. |
| 5 | System roles storage | **Single column** `role` (`USER` / `ADMIN`) | Platform roles are one fact per user. The many-roles-per-user model belongs to org/project membership (Phases 3–4). A join table would add a collection load to every Basic-authenticated request. |
| 6 | Login history table | **`security_events`** with `event_type` | Same cost as `login_events`, and it closes the Phase 0 "security audit log has no home" debt. |
| 7 | Duplicate email at registration | ⏳ **Open**, decide at the start of §1.3 | 409 vs always-202. |
| 8 | Token storage | **One `user_tokens` table**, `purpose` + `CHECK` | The rules are identical for verification and reset. |
| 9 | Name of the security config / chain bean | `TaskflowSecurityConfig` / `taskflowSecurityFilterChain` | Not `defaultSecurityFilterChain`, which is Boot's own default bean name, and Phase 10 adds a second chain. |
| 10 | Health endpoint access | **Endpoint public, details `ADMIN`-only** | Probes are anonymous; details (DB vendor, disk path) are reconnaissance. Restrict the *details*, not the *endpoint*. |
| 11 | Users table / entity name | **`user_accounts`** / **`UserAccount`** | `user` is reserved in Postgres, and `User` clashes with Spring Security's class. FKs to it will be `user_account_id`. |
| 12 | Credential erasure (`CredentialsContainer`) | **Not implemented** | A plain class's default `toString()` doesn't print the hash, and the stateless context is never stored or serialised. |
| 13 | Creating test users | **`TestUsers`, a plain helper class, not a bean**. ADMIN promotion and locking done with SQL. | Registering a helper bean changes the context configuration and starts a second application + container. SQL mirrors how a real first admin is created. |
| 14 | 72-byte password check | **Moved to §1.3** | It's boundary validation, and the registration DTO is where it lives. |

---

## 1. What we covered

| § | Built | Key artifacts |
|---|---|---|
| **1.1** | Security starter, one filter chain, deny-by-default URL rules, stateless Basic, CSRF and logout off, health details restricted, security tests | `TaskflowSecurityConfig`, `management.endpoint.health.roles`, `TaskflowSecurityConfigTest` (19-row slice), `TaskflowSecurityIntegrationTest` |
| **1.2** | `user_accounts` schema, `UserAccount` entity, `DelegatingPasswordEncoder`, `Clock` bean, `TaskflowPrincipal`, `TaskflowUserDetailsService` (Basic now checks real users), email normalisation, auditor writes the app username | `V2__create_user_accounts.sql`, `UserAccount`, `UserRole`, `UserAccountRepository`, `TimeConfig`, `Normalize`, `TaskflowPrincipal`, `TaskflowUserDetailsService`, `AuditAwareImpl`; tests: `TestUsers`, `TaskflowUserDetailsServiceTest`, `AuditAwareImplTest`, `NormalizeTest`, `UserAccountRepositoryTest` |

**Working end to end (verified with curl and tests):**
- Anonymous `/api/v1/**` → 401 + `WWW-Authenticate: Basic`, and still carries `X-Correlation-Id`.
- Correct Basic credentials → 200. A wrong password → 401.
- The six `/api/v1/auth/*` paths are open to POST only.
- An undeclared path → 401 anonymous, 403 authenticated (even as ADMIN).
- `/actuator/health` (plus liveness/readiness) and `/actuator/info` are public; health details are shown to ADMIN only.
- `/actuator/metrics` → ADMIN only.
- No `Set-Cookie` on any response. Security headers present by default.
- *(§1.2)* Basic authenticates against `user_accounts`: case-insensitive email, bcrypt password, **unverified → 401**, **locked → 401**, lock lifts at `locked_until`. Role and lock changes in the DB apply on the very next request.
- *(§1.2)* An admin (`role = 'ADMIN'`) reads `/actuator/metrics` and sees health `components`; a user gets 403 / no details.
- *(§1.2)* `created_by` records the caller's **app username** (`alice`), not their email; `"system"` when nobody is logged in.
- *(§1.2)* Boot's generated password is gone from the startup log.

**Commits:** `9211684` (§1.1 code) · `56340ae` (docs).

---

## 2. Challenges we faced

### The theme of §1.1: a security config that starts cleanly and fails per request

Request matchers are **resolved lazily**, on the first request that reaches them. A green startup says nothing about whether the rules are valid. Two separate bugs proved it:

| Bug | What actually happened |
|---|---|
| `requestMatchers(POST, "/api/v1/auth/{register, login, …}")` | `{…}` in a Spring path pattern **captures a variable**; it isn't a list of alternatives. `PatternParseException: Char ',' is not allowed in a captured variable name`, thrown at **request** time. It was the first rule, so **every POST in the app** returned 500, including `POST /organizations`. The exception is thrown inside the filter chain, so `GlobalExceptionHandler` never saw it. Tomcat dispatched to `/error`, **the security chain ran again, the pattern threw again**, and the client got Tomcat's raw **HTML** 500 page. *Cause:* the requirements table used shell-style shorthand for the six paths. *Fix:* six separate patterns in one `requestMatchers` call. |
| `EndpointRequest.to("health/**", "info")` | `to(String…)` takes endpoint **IDs** (`health`), not paths. `IllegalArgumentException: 'value' must only contain valid chars` from `EndpointId`, again at request time. Every request that got past the POST rule died on it, so **every GET** returned 500. *Fix:* the IDs (`"health"`). `EndpointRequest` already matches the endpoint's own path *and* its sub-paths, so `/health/liveness` is covered without `/**`. The type-safe overload (`HealthEndpoint.class`) makes a typo a compile error instead. |

💡 **The lesson:** after any change to the security config, run the whole access table, not just the endpoint you touched. Better still, make the table a test (§1.1 now has one). Either bug fails a slice test in under a second.

### Rules that were valid but meant something else

| Written | Meant | Consequence |
|---|---|---|
| `.anonymous()` on health and the auth endpoints | `.permitAll()` | `anonymous()` means **only** unauthenticated callers. A logged-in caller got **403** from `/actuator/health`, so `show-details: when_authorized` could never take effect, and a client sending valid Basic credentials to `/auth/login` would be refused. |
| `EndpointRequest.to("health", "info", "metrics").hasRole("ADMIN")` | Restrict health **details** to ADMIN | The whole endpoint became admin-only. Liveness and readiness probes are anonymous → they'd get 401 → the orchestrator restarts a healthy app, over and over (the restart loop from the Phase 0 log). *Fix:* endpoints `permitAll`, plus `management.endpoint.health.roles: ADMIN`. |
| `show-details: when_authorized` with no `roles` | "Admins see details" | It means **any authenticated user**. With self-registration coming in §1.3, every account could read the DB vendor, the server's absolute filesystem path and its disk usage. |

### Tests and the build

**The app wouldn't start from IntelliJ once tests were red.** `.idea/workspace.xml` has *"Delegate IDE build/run actions to Maven"* on, needed for `/actuator/info`'s `git.properties`. That build ran the test phase. *Resolution:* `./mvnw spring-boot:run`, which compiles tests but never runs them, or *Maven → Runner → Skip Tests* for IDE runs only. Starting the app and running the suite are separate jobs.

**`./mvnw spring-boot:run --debug` doesn't pass `--debug` to the app.** Maven takes it as **its own** flag (`-X`). The Phase 0 log's command was wrong and has been corrected. Correct form: `-Dspring-boot.run.arguments=--debug`.

**Adding only the starter broke 6 of 13 tests, with different statuses for the same cause.**

| Test | Got | Why |
|---|---|---|
| Slice GET | 401 | Boot's default chain: everything authenticated |
| Slice POST | **403** | Default chain has CSRF on |
| Integration GET | 401 | Anonymous |
| Integration POST | **401 or 302** | The same CSRF 403 → `sendError` → real Tomcat runs an **`ERROR` dispatch to `/error`** → the default chain secures `/error` → the anonymous caller gets an entry point instead. The **302** is the default chain's form-login entry point redirecting to `/login`, chosen from the `Accept` header. |

MockMvc never performs the `ERROR` dispatch, so the slice shows the raw 403. **Same cause, different status, depending on the kind of test.**

**`application-test.yml` was in `src/main/resources`.** It was empty since Phase 0, so its location didn't matter. The moment it held `spring.security.user` with role `ADMIN`, **every jar shipped a known admin login**, one misconfigured `SPRING_PROFILES_ACTIVE=test` away from live. *Fix:* moved to `src/test/resources`, and verified `target/classes` no longer contains it after `clean`.

**Feature tests ran as ADMIN.** If `/api/v1/**` were accidentally changed to `hasRole("ADMIN")`, every normal user would be locked out, and those tests would still pass, because they ran as the only role the bug allows. *Fix:* the weakest role that should succeed (`USER`).

**Two things a slice can't test (verified):**

| Request | In `@WebMvcTest` | In the real app | Why |
|---|---|---|---|
| Anonymous `GET /actuator/health` | 401 | 200 | The slice doesn't load actuator. `EndpointRequest` finds no endpoints, **silently matches nothing**, and the request falls through to `denyAll()`. |
| `ADMIN` `GET /actuator/metrics` | 403 | 200 | Same reason |
| `httpBasic("test-user", "test-password")` | 401 | 200 | The slice has no `@ActiveProfiles("test")`, so it runs as **`dev`**, where `test-user` doesn't exist |

→ Actuator rules and real credentials are tested in the integration test. The slice uses `with(user(…))`, which tests **authorization** only.

### §1.2: the principal and the auditor looked right and quietly weren't

| Bug | What actually happened | Caught by |
|---|---|---|
| Principal flags never read | `TaskflowPrincipal` had `enabled` / `accountNonLocked` fields but didn't override `isEnabled()` / `isAccountNonLocked()`. **Since Spring Security 6.3 those are `default` methods returning `true`**, so it compiled and the fields were ignored. Locked and unverified users would have logged in. | Code review |
| Flags hard-coded `true, true`; authorities `null` | Same effect for the flags. With no authorities, no user had `ROLE_ADMIN`, so a real admin would get 403. | Code review |
| **Lock check inverted** | `!now.isAfter(lockedUntil)` means *now ≤ lockedUntil*, i.e. **during** the lock. Locked users could log in while locked and were locked **forever** after it expired. Fixed with `!now.isBefore(lockedUntil)`: unlocked from `locked_until` onward. | Code review; now the `lockBoundary` test |
| **Auditor wrote emails** | `authentication.getName()` returns the principal's `getUsername()`, which **is the email**. Observed on a real run: `created_by = alice@example.com`. PII in every audit column, and Phase 0 decision #9 broken. An `isAuthenticated()` check also lets `AnonymousAuthenticationToken` through (it reports itself authenticated). Fixed: `TaskflowPrincipal` → `getAppUsername()`; anything else → `"system"`. | Deliberate failure (manual run) |
| No way to create a `UserAccount` | Only the protected JPA constructor and no setters. Fixed with a factory that also sets `role = USER` and `timezone = "UTC"` in Java. | Code review |

⚠️ **DB defaults don't apply to Hibernate inserts.** `default 'USER'` only works when an `INSERT` leaves the column out. Hibernate always sends every mapped column, so an unset Java field is sent as `NULL` and violates NOT NULL. It's the same family as Phase 0's `insertable = false` bug. The DB defaults serve hand-written SQL.

**Once your own `UserDetailsService` existed, `spring.security.user` stopped working.** Boot's generated user backs off (the §1.1 `--debug` finding), so the test credentials in `application-test.yml` became dead config and the integration tests went 401. *Fix:* the file was deleted; tests create real users with `TestUsers`.

**Creating a dev user by hand has two traps:**
- **The table has no id default.** Use `nextval('global_id_seq')`, the sequence Hibernate uses. A manually taken value is never handed out again, so there's no collision.
- **`psql -c "…"` corrupts bcrypt hashes.** Inside double quotes the shell expands `$2y`, `$10` and so on. Paste into an interactive `psql` instead.

### Found along the way

**`LogoutFilter` is on by default** and appeared in the filter list without being asked for. It handles `/logout` **before** `AuthorizationFilter`, so `denyAll()` doesn't govern it. Disabled; `/logout` now falls to `denyAll` (verified: 401 / 403). Phase 2 builds the real logout.

---

## 3. Production practices implemented, and what each prevents

| Practice | What it prevents |
|---|---|
| **`anyRequest().denyAll()`** as the last rule | "Authenticate-by-default": any endpoint anyone adds is silently open to every logged-in user. `denyAll` forces every path to be declared. |
| Rules ordered specific → general | A broad rule (`/api/v1/**`) swallowing a specific one (`/api/v1/auth/register`). First match wins. |
| **Method-restricted permit rules** (POST only on auth paths) | A permit rule silently opening GET/PUT/DELETE on the same path |
| `permitAll()` rather than `anonymous()` for public endpoints | Logged-in callers being refused access to public endpoints |
| `/error` permitted | An `ERROR` dispatch being denied, turning every error into a bare 401 with an empty body |
| `SessionCreationPolicy.STATELESS` (verified: no `Set-Cookie`) | Server-side session state, and the session-fixation / CSRF exposure that comes with it |
| CSRF disabled **with the reason written down** | Cargo-culting; and forgetting to turn it back on if cookies or sessions ever appear |
| Logout disabled until it's built | An undeclared endpoint running outside the authorization rules |
| Security headers left at defaults (`nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-store`) | MIME sniffing, clickjacking, authenticated responses cached by proxies |
| **`EndpointRequest`** for actuator rules | Rules and endpoints drifting apart when `base-path` or the management port changes |
| Health endpoint public, **details `ADMIN`-only** (`health.roles`) | Probe-driven restart loops (if the endpoint is locked) and infrastructure reconnaissance (if the details are open) |
| Test config in **`src/test/resources`** | Test credentials shipped in the production artifact |
| Tests run as the **weakest role that should pass** | Authorization bugs hidden by tests running as admin |
| **One security test per rule, as boundary pairs** | Rules that fail only at request time; rules that let *everyone* through still passing a "should pass" test |
| **Mutation-checked tests** (8/8 planted bugs caught) | Tests that pass whether or not the rule exists |
| *(§1.2)* **`DelegatingPasswordEncoder`** (`{bcrypt}` prefix) | A big-bang rehash when the algorithm changes: old hashes keep verifying, new ones use the new default |
| *(§1.2)* **Password-hash `CHECK` for the `{id}` prefix** | A hand-inserted hash that no encoder can verify (it now fails at insert, not at login) |
| *(§1.2)* **Email normalised in one place, with `Locale.ROOT`**, plus a lowercase `CHECK` | Duplicate accounts differing only in case; the Turkish-locale `I → ı` bug; a code path that forgot to normalise |
| *(§1.2)* **Username `CHECK` forbids `@`** | A username equal to someone else's email, making one login identifier match two accounts |
| *(§1.2)* **Principal is a separate, immutable class** carrying id + app username | Detached entities in the security context; an extra DB query per request to find "who is calling" |
| *(§1.2)* **`ROLE_` prefix added where authorities are built** | A real admin getting 403 from `hasRole("ADMIN")` |
| *(§1.2)* **Injected `Clock`**; entities receive an `Instant`, never the clock | Time rules testable only by sleeping; untested boundaries |
| *(§1.2)* **Auditor checks the principal type**, not `isAuthenticated()` | `anonymousUser` or email addresses in audit columns |
| *(§1.2)* **No seed users in `V`-migrations**; first admin by `UPDATE` | A known admin password shipped to production |
| *(§1.2)* **Security context cleared in `@AfterEach`** in tests that set it | A ThreadLocal user leaking into the next test on a reused thread |
| *(§1.2)* **The shared test password is bcrypt-hashed once**, not per user | A slow suite: bcrypt costs ~50–100 ms per hash by design |

---

## 4. Learning at each stage

**§1.1 — The filter chain.**

*Where security sits.* Tomcat's filter chain contains one Spring bean, `DelegatingFilterProxy` (order −100, bean name `springSecurityFilterChain`). It exists because Tomcat creates its own filters and knows nothing about Spring. It hands off to `FilterChainProxy`, which runs the **firewall**, clears the `SecurityContext` in a `finally` (the same pattern as the MDC fix), and picks the **first** `SecurityFilterChain` whose matcher fits. `CorrelationIdFilter` runs at highest precedence, **outside** all of this, so rejected requests are still traceable. That's the Phase 0 filter-vs-interceptor decision paying off (verified: a 401 carries `X-Correlation-Id`).

*The chain we got* (from the startup log, with logout since disabled): `DisableEncodeUrlFilter`, `WebAsyncManagerIntegrationFilter`, `SecurityContextHolderFilter`, `HeaderWriterFilter`, ~~`LogoutFilter`~~, `BasicAuthenticationFilter`, `RequestCacheAwareFilter`, `SecurityContextHolderAwareRequestFilter`, `AnonymousAuthenticationFilter`, `SessionManagementFilter`, `ExceptionTranslationFilter`, `AuthorizationFilter`.

*Three requests traced:*
- **No credentials:** Basic skips it → Anonymous sets an `AnonymousAuthenticationToken` → Authorization says no → `ExceptionTranslationFilter` sees *anonymous* → **entry point** → 401.
- **Wrong password:** `BasicAuthenticationFilter` calls the entry point **itself** and stops → 401. It never reaches Authorization.
- **Right password, wrong role:** Authorization says no → `ExceptionTranslationFilter` sees *authenticated* → **access-denied handler** → 403.

*401 vs 403.* 401 = "I don't know who you are". 403 = "I know who you are, and no". `denyAll()` returns **401 to an anonymous caller**: the filter can't forbid someone it hasn't identified, so it asks them to authenticate first.

*Your chain replaces Boot's: two auto-configurations back off independently.* Verified in the Boot 3.5.16 source:

| Auto-configuration | Provides | Backs off when |
|---|---|---|
| `SpringBootWebSecurityConfiguration` via `@ConditionalOnDefaultWebSecurity` | The default chain: everything authenticated + form login + Basic | `@ConditionalOnMissingBean(SecurityFilterChain.class)`: any `SecurityFilterChain` bean exists |
| `UserDetailsServiceAutoConfiguration` | The generated `user` + password | `@ConditionalOnMissingBean` of **any** of `UserDetailsService`, `AuthenticationManager`, `AuthenticationProvider`, `AuthenticationManagerResolver`, or a `JwtDecoder`. It also backs off when OAuth2 client, resource server or SAML classes are on the classpath, unless `spring.security.user.name`/`password` is set. |

📌 It isn't only `UserDetailsService`. In §1.5, exposing an `AuthenticationManager` bean for the login endpoint would **also** switch the generated user off. So would adding an OAuth2 resource-server dependency in Phase 2.

*Why `@EnableWebSecurity` was redundant:* the same Boot class has a `WebSecurityEnablerConfiguration` that applies it for you, unless a bean named `springSecurityFilterChain` already exists.

*Your chain starts with only what you enable:* no form login, no login page. The things that *are* on by default (CSRF, headers, anonymous handling, logout) have to be switched off one by one.

*Rules:* checked top to bottom, **first match wins**. Spring Security refuses to start if anything follows `anyRequest()`. But everything else about a matcher is only checked when a request arrives (§2).

*CSRF:* the attack abuses credentials the **browser attaches automatically**. Bearer tokens in a header (Phase 2) aren't attached automatically, so CSRF isn't needed for them. **Basic credentials are**, because browsers cache and resend them. Basic is acceptable only as a temporary, API-client-only bridge.

*`/error` and the ERROR dispatch:* `sendError(…)` makes Tomcat issue a second, internal request to `/error`, and in Boot 3 / Security 6 the chain runs on it too. With `/error` permitted, a 401 gets Boot's JSON (`timestamp`, `status`, `error`, `path`), **not** our `ProblemDetail`. That's the §0.7 note ("advice can't see filter-chain exceptions") in practice, left for Phase 2's `AuthenticationEntryPoint`.

*Testing security* (see `SECURITY_TESTING_GUIDE.md`):
- A slice with the security config and **no controllers** turns "let through" into a **404**, a clean signal for rule tests.
- `user(…)` skips authentication entirely and puts a finished `Authentication` in the context, so it tests authorization only. `httpBasic(…)` / `withBasicAuth(…)` test the real password check.
- The access table works well as a `@ParameterizedTest` with `@CsvSource`: one row per rule, each shown as its own test.

**§1.2 — Users, passwords, the principal, the auditor.**

*Who calls whom.* The filter builds an unauthenticated `UsernamePasswordAuthenticationToken` → `ProviderManager` → `DaoAuthenticationProvider`, which Spring builds for you from exactly **one** `UserDetailsService` bean and **one** `PasswordEncoder` bean. The provider: (1) loads the user through **your** service; (2) pre-checks locked / disabled / expired, **before the password**; (3) checks the password with the encoder; (4) post-checks credentials-expired; (5) returns an authenticated token. The `UserDetailsService` only *finds*; the encoder only *compares*; the provider *orchestrates*; the manager *picks the provider*.

*`UserDetails` flag mapping:* `getUsername()` = the **email** (the login identifier; the method name is historical) · `isEnabled()` = email verified · `isAccountNonLocked()` = `locked_until` null or passed, per the `Clock` · the other two always `true`. The flags are **plain booleans computed when the principal is built**; the principal holds no clock, repository or entity.

*`hasRole` vs `hasAuthority`:* an authority is a string; a role is an authority starting with `ROLE_`. `hasRole("ADMIN")` looks for `ROLE_ADMIN`; `hasAuthority("ADMIN")` for exactly `ADMIN`. Phase 4 will use unprefixed authorities for fine-grained permissions.

*bcrypt:* deliberately slow (cost 10 = 2¹⁰ rounds). The salt is **inside** the hash (`$2a$10$` + 22-char salt + 31-char hash), so the same password hashes differently every time, and `matches()` re-derives the hash with the stored salt. Only the first **72 bytes** count. `{bcrypt}` tells `DelegatingPasswordEncoder` which algorithm verifies it.

*`Clock`:* the current time is a hidden input; `Clock` makes it a dependency. `Clock.systemUTC()` in production (UTC so `LocalDate.now(clock)` never depends on the server's zone); `Clock.fixed(...)` in unit tests. Services compute `Instant.now(clock)` and **pass** it into entity methods. Boundary chosen: unlocked **from** `locked_until` onward.

*Why the principal lives in `common/security`:* the auditor (`common/config`) and, later, every feature read it; only `user` knows how to build one from the entity. Same shape as `ErrorCode`: shared contract in `common`, feature-specific knowledge in the feature.

*The schema as the final guard* (verified inside a rolled-back transaction on dev Postgres): unique email and username; lowercase-email, email-format, username-format (no `@`, lowercase, 3–50), non-blank display name, `{id}`-prefixed hash, role and non-negative-attempts `CHECK`s. `password_hash varchar(200)` is sized for the longest encoder you might migrate to (`{pbkdf2@SpringSecurity_v5_8}` ≈ 124 chars), not for bcrypt's 68. Postgres **silently truncates identifiers over 63 characters**, which would break error mapping by constraint name (the longest here is 39).

*Testing, the new patterns:*
- **Unit + `Clock.fixed`:** the service is built by hand (`new TaskflowUserDetailsService(mockRepo, fixedClock)`), because a clock shouldn't be a mock. The lock boundary is a `@ParameterizedTest` at +600s, +1s, **0s** and −1s.
- **`ReflectionTestUtils.setField`** sets state the entity has no public API for yet (`id`, `lockedUntil`, `role`). It's acceptable in tests; §1.5 will add real lockout methods.
- **The auditor's test** puts an `Authentication` into `SecurityContextHolder` exactly as Spring would, and clears it in `@AfterEach`.
- **The JPA test** reads `role` with a **native query**, the only way to prove it's stored as text: through JPA, `ORDINAL` and `STRING` both look like `UserRole.USER`.

📊 **Measured:**
- *(§1.2)* Suite: **45 → 76 tests**, all green, **9.4s** wall-clock, **2** Postgres containers (one per context type: `@SpringBootTest` and `@DataJpaTest`). The new classes reused existing contexts (JPA test 0.07s, security integration test 1.1s).
- *(§1.2)* **7/7** planted bugs turned the suite red (table in §7). Mapping `role` as `ORDINAL` was caught **before any test ran**: `ddl-auto: validate` refused to start every database-backed context (32 errors).
- Suite: **13 → 45 tests**, all green.
- The security integration test reused the cached context: **0.44s** for 13 tests, with no second application start.
- **8/8** planted rule bugs turned the suite red (table in §7).

---

## 5. Interview questions

### Answerable now, with the shape of the answer

1. **What happens between a request arriving and your controller running?** Tomcat filters → `DelegatingFilterProxy` → `FilterChainProxy` (firewall, picks the first matching chain) → the chain's filters in order (context holder, headers, Basic, anonymous, exception translation, authorization) → `DispatcherServlet`.
2. **Why do `DelegatingFilterProxy`, `FilterChainProxy` and `SecurityFilterChain` all exist?** Bridge from the servlet container into Spring beans · one entry point that picks a chain and clears the context · the chains you declare, one per URL space.
3. **401 vs 403, and why does `denyAll()` give an anonymous user a 401?** Unauthenticated vs forbidden. `ExceptionTranslationFilter` sends anonymous callers to the entry point.
4. **Why isn't `anyRequest().authenticated()` deny-by-default?** Every undeclared endpoint is open to every logged-in user. `denyAll()` makes undeclared mean closed.
5. **`permitAll()` vs `anonymous()`?** Everyone vs *only* unauthenticated callers. `anonymous()` forbids logged-in users, which I hit on the health endpoint.
6. **When is disabling CSRF safe?** When credentials aren't attached automatically by the browser, i.e. bearer tokens in a header. Not with cookies or sessions, and not really with Basic in a browser, because browsers resend cached Basic credentials.
7. **Why can't `@RestControllerAdvice` handle a 401 from Basic auth?** It happens inside the filter chain, before `DispatcherServlet`. That needs an `AuthenticationEntryPoint` (Phase 2).
8. **Your security config started fine but every request returned 500. How?** Request matchers are resolved lazily. An invalid path pattern and an invalid endpoint ID only failed when a request reached them. Now caught by one test per rule.
9. **How did you secure the health endpoint?** Endpoint public because probes are anonymous; details `ADMIN`-only via `health.roles`, because `when_authorized` alone means *any* authenticated user.
10. **Liveness endpoint behind authentication: what happens?** The orchestrator can't reach it, marks a healthy instance dead and restarts it, in a loop.
11. **How does Boot's default security back off when you configure your own?** Two independent `@ConditionalOnMissingBean`s: a `SecurityFilterChain` bean replaces the default chain; a `UserDetailsService`, `AuthenticationManager` or `AuthenticationProvider` bean removes the generated user.
12. **How do you test security rules?** One test per rule as boundary pairs, the table as a parameterised test, slice for URL rules, integration for actuator and real credentials, and mutation checks to prove each test can fail.
13. **Why do your feature tests run as a plain user, not admin?** Least privilege: a test running as admin can't detect a rule that wrongly requires admin.
14. **Why is `STATELESS` not enough on its own to guarantee no sessions?** It governs Spring Security only; other code can still create a session. So verify it: no `Set-Cookie`.
15. **`AuthenticationManager` vs `AuthenticationProvider` vs `UserDetailsService` vs `PasswordEncoder`?** The manager picks a provider that supports the token; the provider orchestrates; the service only finds the user; the encoder only compares hashes.
16. **How is a password stored? Why is the same password hashed differently each time?** bcrypt with a per-hash random salt stored inside the hash; `matches()` re-derives using that salt. Deliberately slow via the cost factor.
17. **What's the `{bcrypt}` prefix for, and how would you migrate everyone to argon2?** `DelegatingPasswordEncoder` picks the verifier by prefix, so old hashes keep working; change the default and rehash on next successful login (`upgradeEncoding` + `UserDetailsPasswordService`).
18. **`hasRole` vs `hasAuthority`?** `hasRole` adds `ROLE_`. Without the prefix in the authorities, a real admin gets 403.
19. **Why doesn't your entity implement `UserDetails`?** The principal lives in the security context outside any transaction: an entity there is a detached, stale row with lazy associations. A small immutable principal carries id, app username and flags.
20. **Where is the logged-in user stored, and what's in it?** In the `SecurityContext` (a ThreadLocal, cleared per request): an `Authentication` whose principal is our `TaskflowPrincipal`.
21. **Your `created_by` column filled with email addresses. Why?** `getName()` returns the principal's `getUsername()`, which is the login email. The auditor now reads the app username from our principal type, and returns `"system"` for anything else, including the anonymous token that claims to be authenticated.
22. **Why `Locale.ROOT` when lowercasing?** The default-locale `toLowerCase()` turns `I` into dotless `ı` under Turkish, silently creating a different email.
23. **How do you test code that depends on the current time?** Inject a `Clock`; tests use `Clock.fixed` and test the exact boundary instant.
24. **Your column has `DEFAULT 'USER'`, yet the insert failed with a NOT NULL error. Why?** Hibernate sends every mapped column, including unset ones as `NULL`; defaults only apply to inserts that omit the column.
25. **How is the first admin created?** By hand: an `UPDATE` in the database, never a seed user in a versioned migration (that would ship a known password to production).

### Not answerable yet

- Design a password-reset token. — §1.4 / §1.6
- How does your lockout survive a failed authentication rolling back? — §1.5
- Why SHA-256 for tokens but bcrypt for passwords? — §1.4 (half-answered: bcrypt is salted, so it can't be looked up by hash)

---

## 6. Commands

```bash
# Run the app (tests compile but don't run)
./mvnw spring-boot:run
./mvnw spring-boot:run -Dspring-boot.run.arguments=--debug | tee target/boot-debug.log   # auto-config report

# Check the access rules by hand (generated password is in the startup log)
curl -i localhost:8080/api/v1/organizations                        # 401, WWW-Authenticate, X-Correlation-Id, security headers
curl -i -u user:<generated-password> localhost:8080/api/v1/organizations
curl -s localhost:8080/actuator/health                             # summary only, no "components"

# Tests
./mvnw test -Dtest='TaskflowSecurity*'                             # the security tests only
./mvnw clean test                                                  # everything; clean also proves test config isn't in target/classes

# Read Boot's own source when a condition is unclear (sources jar is in ~/.m2)
unzip -p ~/.m2/repository/org/springframework/boot/spring-boot-autoconfigure/3.5.16/spring-boot-autoconfigure-3.5.16-sources.jar \
  org/springframework/boot/autoconfigure/security/servlet/UserDetailsServiceAutoConfiguration.java | grep -A3 ConditionalOnMissingBean
```

Creating a dev user by hand (§1.2):

```bash
htpasswd -bnBC 10 "" 'alice-dev-pass' | tr -d ':\n'; echo       # bcrypt hash ($2y$…); prefix it with {bcrypt}
docker compose exec postgres psql -U taskflow -d taskflow       # then paste the INSERT interactively
```

```sql
insert into user_accounts (id, email, username, display_name, password_hash, role,
                           email_verified_at, password_changed_at, created_at, updated_at, created_by, updated_by)
values (nextval('global_id_seq'), 'alice@example.com', 'alice', 'Alice', '{bcrypt}<HASH>', 'USER',
        now(), now(), now(), now(), 'system', 'system');
update user_accounts set role = 'ADMIN' where username = 'alice';                                  -- promote
update user_accounts set locked_until = now() + interval '10 minutes' where username = 'alice';   -- lock
```

Logging switches for one-off investigation (dev only; switch them off again):
- `org.springframework.security.web.DefaultSecurityFilterChain: DEBUG` prints the filter list at startup.
- `org.springframework.security: TRACE` follows one request filter by filter.

---

## 7. Deliberate failures — the strongest material

| Broke | Result |
|---|---|
| Starter added, no config | 6 of 13 tests red: slice 401 / 403 (CSRF), integration 401 / **302** (ERROR dispatch + form-login entry point). Generated password in the log. |
| Brace pattern in `requestMatchers` | **Every POST** → Tomcat HTML 500; startup clean |
| `EndpointRequest.to("health/**")` | **Every GET** → 500; startup clean |
| `anonymous()` instead of `permitAll()` | Logged-in caller → 403 on `/actuator/health` |
| *(§1.2)* Auditor using `authentication.getName()` | `created_by = alice@example.com`: the login email in an audit column |

**Mutation checks: each bug planted in a scratch copy; every one turned the suite red.**

| Planted | Tests failed |
|---|---|
| auth endpoints `permitAll` → `anonymous` | 1 |
| `denyAll` → `authenticated` | 3 |
| POST restriction dropped | 2 |
| health/info ADMIN-only | 6 |
| `health.roles: ADMIN` removed | 1 |
| `/error` permit removed | 1 |
| CSRF re-enabled | 11 |
| `/api/v1/**` → ADMIN only | 4 |

**§1.2 mutation checks (7/7 caught):**

| Planted | Caught by |
|---|---|
| Lock check inverted (`isAfter`) | `lockBoundary` (unit) + locked user → 401 (integration) |
| Auditor uses `getName()` | auditor unit test + organization `createdBy` (integration) |
| `isEnabled()` override removed | unverified unit test + unverified user → 401 (integration) |
| `ROLE_` prefix dropped | authority unit test + `metrics_allowsAdmins` (integration) |
| `Locale.ROOT` dropped | `NormalizeTest` Turkish-locale test |
| Email not normalised in the service | unit normalisation test + case-insensitive login (integration) |
| `role` mapped as `ORDINAL` | **`ddl-auto: validate`**: every DB-backed context refused to start (32 errors) |

---

## 8. Carried debt

- **Decision 7 (duplicate-email response)**: settle at the start of §1.3.
- **The 72-byte password check**: moved to §1.3 (the registration DTO).
- **401/403 bodies are Boot's error JSON, not `ProblemDetail`**: Phase 2 (`AuthenticationEntryPoint`, `AccessDeniedHandler`).
- **OpenAPI**: still deferred; do it after Phase 2 so the security scheme is documented once. `/v3/api-docs` and `/swagger-ui` must be permitted in `dev` only.
- **The CSRF comment** in `TaskflowSecurityConfig` runs two ideas together. Reword.
- `org.springframework.security: TRACE` is left **commented** in `application-dev.yml`. Harmless; delete when tidying.
- 📊 **bcrypt timing** at cost 10 vs 12: not measured yet.
- **Nits in §1.2 code:** unused `UserDetails` import in `AuditAwareImpl`; the private `setRole` / `setTimezone` in `UserAccount` are now unused (the constructor assigns directly).
- **README**: auth endpoints and the Basic-auth note are due by the end of Phase 1 (definition of done).
- From Phase 0, still open: the `Organization` no-arg constructor should be `protected` (`UserAccount`'s already is).
