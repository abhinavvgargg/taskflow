# Phase 1 — Users & Auth Core (Requirements)

> Companion to `../PROJECT_CONTEXT.md` · decisions and traps from `../phase-0/PHASE_0_LEARNING_LOG.md` · test patterns from `../phase-0/TESTING_GUIDE.md`. Requirements only, no code. Tick the boxes as you go.
> **Time-box: 7h, tests included. Hard stop at 10.5h (150%).** Anything unfinished becomes a side task in Phase 2. The phase doesn't get extended.

**The goal of Phase 1 is identity you can trust.** By the end, a user can register, prove they own their email, log in, get locked out after repeated failures and unlocked automatically, reset a forgotten password and change a known one. Every protected request knows **who** is calling, and every audit column records it. Phase 2 swaps the transport (HTTP Basic → JWT) without touching any of this.

⚠️ **Trap, the one that defines this phase:** security code that "works" in the happy path. A login endpoint that returns 200 for the right password is maybe 20% of the job. The rest is what happens with a wrong password, with an unknown email, when the same token gets used twice at once, and when an attacker compares response times. **Every section below has a test for the failure path, not just the success path.**

---

## Carried in from Phase 0

| Item | What to do in Phase 1 |
|---|---|
| `AuditAwareImpl` returns `"system"` | Replace it with the authenticated user's **username**. §1.2 has the trap. |
| `--debug` auto-configuration report never read | §1.1 makes you read it. Adding the security starter gives you a reason to. |
| "Decide whether echoing `rejectedValue` is safe" | It isn't. Passwords now pass through validation, so **confirm** the handler still never echoes values, and add a test that locks that in. |
| Security-action audit log has no home in the plan | §1.5 gives it one. See decision 6. |
| OpenAPI deferred | **Still deferred.** Do it after Phase 2, so the security scheme gets documented once (bearer) rather than twice (basic, then bearer). When it lands, `/v3/api-docs` and `/swagger-ui` must be allowed through the filter chain in `dev` only. |
| 401/403 bypass `@RestControllerAdvice` (noted in §0.7) | You'll **see** it this phase, and it's still Phase 2's to fix. §1.1. |

---

## Decisions (recorded 2026-09-24)

| # | Decision | Chosen | Options considered · the reasoning |
|---|---|---|---|
| 1 | **How authenticated requests work before JWT exists** | **HTTP Basic, stateless**, plus `POST /auth/login` | Options were Basic, server sessions, or no protected endpoints until Phase 2. Basic lets you build and test `/me` now. The login endpoint is the seam Phase 2 extends to issue tokens, and it teaches you to call `AuthenticationManager` directly. Basic gets deleted in Phase 2. |
| 2 | **Package layout** | **`common/security` + `user`** | `common/security` holds the filter chain config, the principal type and the `PasswordEncoder` bean. `user` holds the entity, repository, `UserDetailsService` implementation, registration, tokens, lockout and the login endpoint. The principal type **has to live in `common`**, because `AuditAwareImpl` in `common/config` reads it and `common` never imports a feature. Same reasoning as the `ErrorCode` interface. |
| 3 | **What the user logs in with** | **Email.** The "either" approach is studied too (see §1.2, *Logging in with either*). | Keep a separate **username** anyway. Phase 0 decision #9 says `created_by` holds a username, and Phase 9 `@mentions` need one. |
| 4 | **Principal type** | **A separate principal class** (`TaskflowPrincipal`; built as a class, not a record) | The principal lives in the `SecurityContext` for the whole request, outside any transaction. If it's an entity, it becomes a detached entity with lazy associations waiting to throw (Phase 3+), and a stale copy of a row. Carry only id, username, email, password hash, role and the two status flags. |
| 5 | **System roles** | **Single column** `role` (`USER` / `ADMIN`), decided 2026-09-24 | These are platform roles, one fact per user. The many-roles-per-user model belongs to org and project membership (Phases 3–4), which get their own tables. A join table would add a collection load to every Basic-authenticated request for no gain. |
| 6 | **Login history table** | **`security_events`** with an `event_type` column | It costs the same as `login_events`, and it closes the Phase 0 "audit log has no home" debt. The login-history endpoint filters it down to login types. |
| 7 | **Registration with an email already registered** | **409 `EMAIL_ALREADY_REGISTERED`**, decided 2026-09-25 | Clear for a person who forgot they have an account. It knowingly lets *this* endpoint reveal registered emails; login and reset still must not, and Phase 10 rate limiting blunts mass probing. The alternative (always 202 + email the owner) needs the §1.4 email sender and gives a worse experience. |
| 8 | **Token storage** | **One `user_tokens` table**, `purpose` constrained by a `CHECK` | The rules (hashed, expiring, single-use, invalidated on reissue) are identical for both purposes. |
| 9 | **Password minimum length** (§1.3) | **12 characters**, max 72 UTF-8 bytes, no composition rules, decided 2026-09-25 | Length is what makes a password strong. 12 sits between the traditional 8 and NIST's newer 15 for password-only login. The byte cap is bcrypt's real limit. |
| 10 | **Username case** (§1.3) | **Any case accepted, lowercased in the service**, decided 2026-09-25 | Same rule as the email, so `Alice` and `alice` can't be two accounts, and mobile auto-capitalisation doesn't cause failed sign-ups. The DB `CHECK` guards the stored form. |
| 11 | **Token in the email link** (§1.4) | **URL fragment** (`#token=…`), decided 2026-09-26 | Never sent to a server: not in access logs, proxy logs or `Referer`. The frontend reads it and POSTs it. |
| 12 | **Old tokens on reissue** (§1.4) | **Deleted** (same user, same purpose, unused) | Only the latest link works, and the table doesn't grow per resend. The history of security actions belongs in `security_events` (§1.5). |
| 13 | **"Send after commit"** (§1.4) | **A separate, non-transactional `RegistrationWorkflow` bean** | Explicit and testable; avoids self-invocation. Phase 9 replaces it with `@TransactionalEventListener(AFTER_COMMIT)`. |
| 14 | **Email verification token lifetime** (§1.4) | **24 hours** (typed config) | Long enough for "I'll do it tonight"; the token is single-use and purpose-bound. |
| 15 | **Email fails after the account is saved** (§1.4) | **Log at ERROR (account id only), still 201** | The account exists; resend is the recovery path. Rolling back would need the send inside the transaction, which brings back the phantom email. |
| 16 | **`UserToken` → `UserAccount`** (§1.4) | **`@ManyToOne(fetch = LAZY)`** | A real association, without `@ManyToOne`'s default eager load on every token read. |

---

## Concepts you need first (the *why*, briefly)

💡 **The filter chain, end to end.** Tomcat's filter chain contains one Spring bean, `DelegatingFilterProxy` (bean name `springSecurityFilterChain`). It hands off to `FilterChainProxy`, which picks the first `SecurityFilterChain` whose matcher fits the request and runs **its** ~15 filters in order. Your `CorrelationIdFilter` runs **before** all of this, because it's ordered at highest precedence and security sits at `-100`. That's the Phase 0 filter-vs-interceptor decision, finally paying off. **Why it matters:** every security question you'll be asked ("where does the JWT get validated?", "why didn't my advice catch this 401?") starts with *which filter, in which order*.

💡 **How a password gets checked.** A filter pulls the credentials out of the request and wraps them in an *unauthenticated* `Authentication` → `AuthenticationManager` (implemented by `ProviderManager`) → it asks each `AuthenticationProvider` whether it `supports()` that token type → `DaoAuthenticationProvider` calls your `UserDetailsService`, runs the **pre-checks** (locked? disabled?), checks the password with your `PasswordEncoder`, runs the **post-checks** → returns an *authenticated* `Authentication` → it's stored in `SecurityContextHolder`. Learn this pipeline by heart. Phase 2 adds a JWT filter to it, and Phase 10 adds a provider of your own.

💡 **`SecurityContextHolder` is a `ThreadLocal`.** It's the same mechanism as MDC, and the same pooled-thread hazard. Spring Security clears it at the end of every request (`SecurityContextHolderFilter`). This is the **second** time you've met the ThreadLocal pattern, with Phase 3 and Phase 9 still to come. In Spring Security 6 a context you set by hand is **not** saved between requests unless a `SecurityContextRepository` saves it. That's why stateless means "authenticate on every request".

💡 **Password hashing.** Use a **deliberately slow**, **salted** hash (bcrypt) so that a stolen table costs years to crack instead of seconds. `DelegatingPasswordEncoder` stores `{bcrypt}$2a$10$…`. The `{id}` prefix lets you change algorithms later without a big-bang migration: old hashes keep verifying, and new ones use the new algorithm. **Why it matters:** "how would you migrate 1M users from SHA-1 to bcrypt?" is a real interview question, and the prefix is the answer.

💡 **Secure token design** (email verification, password reset). This is the part tutorials skip. A token needs to be:
1. **Unguessable.** 32 bytes from `SecureRandom`, Base64URL-encoded. Never a UUID. v4 UUIDs happen to be random, but "UUID" doesn't *promise* unpredictability, and 122 bits leaves less margin.
2. **Hashed at rest with SHA-256, not bcrypt.** A leaked database mustn't hand out working reset links. A *fast* hash is correct here, because the input already has 256 bits of entropy, so there's nothing to brute-force. And you need to **look the token up by its hash**, which a salted bcrypt hash can't do.
3. **Expiring**, **single-use**, **purpose-bound** (a verification token can't reset a password), and **invalidated when a new one is issued**.
4. **Kept out of URLs and logs** wherever you control them.

💡 **Account enumeration.** Any response that differs between "this email exists" and "it doesn't" (status, body, *or timing*) lets an attacker test a list of emails against your system. Spring already guards the login path: `hideUserNotFoundExceptions` turns "no such user" into "bad credentials", and `DaoAuthenticationProvider` hashes a dummy password when the user doesn't exist so the timing matches. 🔍 Find both in the source. Your job is to avoid **undoing** that protection anywhere else.

---

## How each section is laid out (from 2026-09-25)

Every section follows the same order, so you know **what** you're building and **why** before any steps:

1. **What we're building**: the feature in plain words, and what's in and out of scope.
2. **How we're building it, and why**: the moving parts, and for each key choice the problem it solves, what it avoids, and the alternative we didn't take.
3. **What we're optimising for**: the qualities we trade other things for.
4. **Concepts**: what you need to understand first.
5. **Requirements**: the checklist.
6. **Traps** ⚠️: the mistakes with real consequences, including the ones we actually hit.
7. **Deliberate failures**: bugs to create on purpose, see, then fix. They become interview stories.
8. **Build order**
9. **Tests**

§1.1–§1.3 are written this way; §1.4 onward get the same treatment when we reach them.

---

## 1.1 — Security starter & the filter chain ✅ done (commit `9211684`)

### What we built

A **gate in front of the whole application**. Before §1.1, every endpoint was open to anyone. After it, every request passes through Spring Security, which decides one of three things:
- **let it through** (public endpoints, or a correctly authenticated caller)
- **401**: "I don't know who you are"
- **403**: "I know who you are, and no"

No users existed yet, so it ran against Boot's generated test user; §1.2 replaced that with real accounts. Out of scope: JSON bodies for 401/403, JWT, CORS (all Phase 2), and per-resource permissions (Phase 4).

### How we built it, and why

| Choice | Problem it solves / avoids |
|---|---|
| **One `SecurityFilterChain` bean** with explicit URL rules | Boot's default chain (everything authenticated, form login, CSRF on) is built for server-rendered pages, not a JSON API |
| **HTTP Basic, stateless** (decision 1) | Authenticated endpoints can exist and be tested before JWT (Phase 2). No sessions: no server-side state, no session cookie to steal. |
| **`denyAll()` as the last rule** | An endpoint someone forgets to declare is **closed**, not silently open to every logged-in user |
| **Auth endpoints: POST only, exact paths, `permitAll`** | Exactly six operations are open to anonymous callers, and nothing near them |
| **Health/info public, health *details* `ADMIN`-only** | Probes (always anonymous) keep working; the DB vendor and disk paths stay hidden |
| **`EndpointRequest`** for actuator rules | Rules keep matching if the actuator base path or port changes |
| **CSRF off, with the reason written down** | API clients send credentials explicitly, so there's nothing for CSRF to protect, and the reason is on record for when that changes |
| **`/error` permitted** | Tomcat's internal `ERROR` dispatch passes through security too; permitting it keeps real error statuses and bodies |

#### Alternatives we didn't take

| Alternative | Why we didn't take it | The problem it would cause later |
|---|---|---|
| **Keep Boot's default chain** and patch around it | Its defaults (form login, CSRF on, everything authenticated) don't fit an API. You'd be switching things off one at a time without knowing what's still on. | API clients get **302 redirects to `/login`** (seen in §1.1's test run) and **403 on every POST**. In Phase 2, the JWT filter lands in a chain whose contents you never chose, which makes ordering bugs hard to diagnose. |
| **Server sessions** (`JSESSIONID`) | State on the server, a session cookie to protect, and CSRF protection required. Phase 2 moves to tokens anyway. | A second instance needs sticky sessions or a shared session store (Redis, which belongs to the microservices track). Session fixation and CSRF surface to defend. Throwaway work before JWT. |
| **No protected endpoints until Phase 2** | Nothing in Phase 1 (`/me`, the auditor, lockout) could be tested through a real request | Phase 2 would build JWT *and* check all of Phase 1's identity features end to end for the first time together. When something breaks, you can't tell which layer did it. |
| **`anyRequest().authenticated()`** as the last rule | It's authenticate-by-default, not deny-by-default | Phases 3–5 add dozens of endpoints. One forgotten rule (an admin or internal endpoint) is open to **every registered user**, and with self-registration (§1.3) that's anyone. No test fails, because "authenticated" passes. |
| **A broad `/api/v1/auth/**` permit** | It opens every current *and future* path under `/auth`, on every HTTP method | A later authenticated endpoint placed under `/auth` (e.g. listing your sessions in Phase 2, API keys in Phase 10) is **public by accident**. |
| **`anonymous()`** for public endpoints | It forbids anyone who *is* logged in | A client that sends its token on every request (normal for SPAs, and in Phase 2) gets **403** from login, reset and health. Monitoring with credentials breaks. *(Hit.)* |
| **Locking the whole health endpoint** to ADMIN | Probes are anonymous | Phase 11's Docker Compose healthcheck (and any Kubernetes probe) gets 401, marks a healthy app dead and **restarts it in a loop**. *(Hit.)* |
| **Health details for any logged-in user** (`when_authorized` without `roles`) | "Authorized" then means *authenticated* | Every self-registered account can read your DB vendor, the server's absolute disk path and disk usage: free reconnaissance. |
| **Hard-coded `/actuator/...` strings** | They describe today's path, not the endpoint | Moving actuator to a separate management port or base path (a common production move, Phase 11) leaves the rules matching nothing. Endpoints fall to `denyAll` (locked out), or worse, were covered by a broader permit. |
| **Leaving CSRF on** | API clients would need to fetch a CSRF token first | Every POST → 403 until clients fetch tokens, which needs a session, reintroducing the state we avoided. |
| **Turning CSRF off without writing down why** | The reason is what tells you when to turn it back on | If a cookie ever carries credentials (e.g. a refresh token in an `HttpOnly` cookie, a real Phase 2 option), nobody remembers that CSRF must come back: a **real CSRF hole**. |
| **Leaving `/error` secured** | The `ERROR` dispatch would be denied | Every error surfaced through `/error` becomes a bare 401 with an empty body. A validation or server error then looks like an authentication failure, which misleads clients and makes debugging hard. |

### What we optimised for

**Closed by default**: forgetting something fails safe. **Every rule proven by a test**, because matchers are checked per request and a green startup proves nothing. **Least exposure** of operational details.

### Concepts

The phase-level concepts above (filter chain, 401 vs 403), plus section 4 of `PHASE_1_LEARNING_LOG.md`: the filter list, three requests traced, the two auto-configurations that back off, CSRF, and the ERROR dispatch.

### Requirements

- [x] Add `spring-boot-starter-security`, and `spring-security-test` in test scope (**not** part of `spring-boot-starter-test`).
- [x] ⚠️ **Deliberate failure:** starter only → every endpoint 401 and a generated password in the log. `--debug` read; both backing-off conditions recorded (learning log §4).
- [x] One `SecurityFilterChain` bean in `common/security`. Nothing extends `WebSecurityConfigurerAdapter` (removed in Security 6).
- [x] **Stateless:** `SessionCreationPolicy.STATELESS`. 📊 Verified: no `Set-Cookie`.
- [x] **CSRF disabled, with the reason written down.** Browsers do cache and resend Basic credentials, so Basic is only acceptable as a temporary, API-client-only bridge.
- [x] **Access rules, deny by default,** in this order:

| Request | Access |
|---|---|
| `POST` to each of: `/api/v1/auth/register` · `/api/v1/auth/login` · `/api/v1/auth/verify-email` · `/api/v1/auth/verify-email/resend` · `/api/v1/auth/password-reset/request` · `/api/v1/auth/password-reset/confirm` | `permitAll` (**POST only**; six separate patterns) |
| `GET /actuator/health/**`, `GET /actuator/info` | `permitAll` |
| `/actuator/metrics/**` | role `ADMIN` |
| `/error` | `permitAll` |
| `/api/v1/**` | authenticated |
| **anything else** | **`denyAll()`** |

- [x] Rules ordered specific → general (first match wins).
- [x] Actuator matched with `EndpointRequest`, using endpoint **IDs** (`"health"`), not paths.
- [x] Security headers left at their defaults.
- [x] Logout disabled until Phase 2 builds it (`LogoutFilter` otherwise handles `/logout` before authorization).
- [x] Health details restricted with `management.endpoint.health.roles: ADMIN`.
- [x] 401 body is Boot's error JSON, not `ProblemDetail`: noted, left for Phase 2.
- [x] 📊 A 401 still carries `X-Correlation-Id` (the Phase 0 filter ordering paying off).
- [x] 🔍 Filter list copied from the startup log (learning log §4).
- [ ] 🔍 Follow one request with `org.springframework.security: TRACE`.

### Traps ⚠️

**Configuration traps:**
- **Disabling CSRF "because the tutorial did".** Know the real reason: CSRF abuses credentials a browser attaches **automatically**. Bearer tokens in a header (Phase 2) never are. **Basic credentials are**: browsers cache and resend them. Basic is acceptable only as a temporary, API-client-only bridge.
- **First match wins.** Put `/api/v1/**` above `/api/v1/auth/register` and registration becomes unreachable, with no error anywhere.
- **`anyRequest().authenticated()` isn't deny-by-default.** It's *authenticate*-by-default: every undeclared endpoint is open to any logged-in user. `denyAll()` makes undeclared mean closed.
- **`/error` is secured too.** Tomcat's `sendError` triggers an internal `ERROR` dispatch that runs through the chain again. If `/error` isn't permitted, every error turns into a bare 401 with an empty body.
- **`permitAll()` vs `anonymous()`.** `anonymous()` means *only* unauthenticated callers, so a logged-in caller gets **403** on a "public" endpoint. *(Hit: health and the auth endpoints.)*
- **Request matchers are resolved per request, not at startup.** A green startup proves nothing about your rules. *(Hit twice, both only visible once a request arrived:)*
  - `"/api/v1/auth/{register, login, …}"`: `{…}` **captures a path variable**; it isn't a list. `PatternParseException` → **every POST** in the app returned Tomcat's HTML 500, bypassing `GlobalExceptionHandler`.
  - `EndpointRequest.to("health/**")`: `to(String…)` takes endpoint **IDs** (`"health"`), not paths. `IllegalArgumentException` → **every GET** returned 500.
- **Locking the whole health endpoint.** Probes are anonymous; they'd get 401 and the orchestrator would restart a healthy app in a loop. Restrict the *details*, not the endpoint. *(Hit.)*
- **`show-details: when_authorized` without `roles`** means **any authenticated user** sees your DB vendor, absolute disk path and disk usage, and with §1.3's self-registration that's anyone.
- **`LogoutFilter` is on by default** and handles `/logout` **before** `AuthorizationFilter`, so `denyAll()` doesn't govern it. Disable it until Phase 2 builds logout.
- **The 401 body isn't your `ProblemDetail`.** It's produced inside the filter chain, before `DispatcherServlet`, so `@RestControllerAdvice` never sees it. Phase 2 (`AuthenticationEntryPoint`).

**Secrets and tests:**
- **A committed credential.** Never put `spring.security.user.password` in a committed `src/main` YAML. And a test profile file in **`src/main/resources` ships inside the production jar**: the `test-user` with role `ADMIN` was one misconfigured `SPRING_PROFILES_ACTIVE=test` from live. *(Hit; moved to `src/test/resources`.)*
- **Tests running as ADMIN** can't catch a rule that wrongly requires admin. Run feature tests as the weakest role that should pass. *(Hit.)*
- **A `@WebMvcTest` slice doesn't load your `SecurityFilterChain` bean** unless you `@Import` it. It gets Boot's default chain instead: **401** on GET, **403 on POST** (CSRF).
- **Actuator rules silently match nothing in a slice.** `EndpointRequest` has no endpoints to match, so requests fall through to `denyAll()`: 401/403 in the slice, 200 in the real app. Test actuator rules in the integration test.
- **A slice runs as the `dev` profile** unless told otherwise, so real test credentials fail there. Test real credentials in the integration test.

**Tooling:**
- **IntelliJ's "Delegate build/run to Maven" runs the tests before starting the app**, so a red suite means no app. Use `./mvnw spring-boot:run`, or *Runner → Skip Tests* for IDE runs only. *(Hit.)*
- **`./mvnw spring-boot:run --debug` gives Maven's debug output**, not the app's. Correct: `-Dspring-boot.run.arguments=--debug`. *(Hit; the Phase 0 log had it wrong.)*

### Deliberate failures

| Create this | What you see | Lesson |
|---|---|---|
| Add **only** the starter, start the app | Every endpoint 401; *"Using generated security password"* in the log; 6 of 13 tests red | Boot's default chain and default user; read `--debug` to see what backs off and why |
| An anonymous **POST before disabling CSRF** | **403**, not 401, even on a permitted path | CSRF rejects before authorization is consulted. The integration test showed **401/302** instead, because of the `ERROR` dispatch and the form-login entry point. Same cause, different status per test type. |
| *(Hit by accident)* The brace pattern, and `EndpointRequest` with a path | Every POST / every GET → 500; startup clean | Matchers are lazy: only requests (or tests) prove rules |
| *(Hit by accident)* `anonymous()` on health | A logged-in caller → 403 | `permitAll` vs `anonymous` |
| **Mutation checks** (8 bugs planted after the tests existed) | Each turned at least one test red | A test only counts if it fails when the bug is present. Table in learning log §7. |

### Build order (as it happened)

Starter only (deliberate failure) → `--debug` → the chain bean → CSRF off (after seeing the 403) → stateless + Basic → the access rules → headers left alone → health details → fix the Phase 0 tests → security tests.

### Tests

`TaskflowSecurityConfigTest` (the access table as a 19-row parameterised slice test; no controllers loaded, so 404 = "let through") and `TaskflowSecurityIntegrationTest` (actuator, real credentials, `/error`, correlation ID, no session). See `SECURITY_TESTING_GUIDE.md`. 8/8 planted rule bugs caught.

🎯 **Interview question:** "What happens between an HTTP request arriving and your controller running, in a Spring Security app?" Answer by naming the filters.

---

## 1.2 — Users, password storage, principal, auditor ✅ done (commit `1fc0f54`)

### What we built

**Real people for the gate to check.** After §1.1, security worked, but only against Boot's one generated test user. §1.2 created:
- a `user_accounts` table
- safe password storage
- the bridge that lets Spring Security authenticate against *our* table

It also made every saved row record **who** saved it. There are still **no endpoints**: users were created by hand with SQL until §1.3. Out of scope: registration (§1.3), verification (§1.4), login endpoint and lockout counting (§1.5).

### How we built it, and why

| Choice | Problem it solves / avoids |
|---|---|
| **`user_accounts` table, `UserAccount` entity** | `user` is reserved in Postgres, and `User` collides with Spring Security's class |
| **Single `role` column** (decision 5) | Platform role is one fact per user; Basic loads the user on every request, so there's no collection to fetch each time |
| **Constraints as the final guard** (lowercase email, username format without `@`, `{id}`-prefixed hash, role values) | Any code path that skips normalisation or validation is still stopped by the database |
| **`DelegatingPasswordEncoder`** (`{bcrypt}…`) | Passwords stored slow and salted, and the algorithm can change later without resetting everyone |
| **Our own `UserDetailsService`** | Spring's `DaoAuthenticationProvider` authenticates against *our* table and returns *our* principal |
| **A separate principal class** (`TaskflowPrincipal`, decision 4) in `common/security` | Carries the id and app username every feature needs, without a detached entity in the security context; in `common` so the auditor and every feature can read it |
| **Flags computed when the principal is built**, using an injected **`Clock`** | Lock and verification rules testable at exact instants, with no sleeping |
| **Email normalised in one place** (`Locale.ROOT`) | One account per address regardless of case or spaces; immune to the Turkish-locale bug |
| **Auditor checks the principal *type*** | `created_by` holds the app username, never the email and never `anonymousUser` |

#### Alternatives we didn't take

| Alternative | Why we didn't take it | The problem it would cause later |
|---|---|---|
| **Table `user` / entity `User`** | `user` is reserved in Postgres, and Spring Security has its own `User` class | Every hand-written or native query needs `"user"` quoted, or it silently queries the *current database user* instead (verified: `select * from user` returns one row, `taskflow`, with no error). In security code, importing the wrong `User` still compiles. (A table called `users` would have worked; `user_accounts` just matches the entity name.) |
| **A `user_roles` join table** | Platform roles don't need many-per-user, and a collection costs a load on every request | An extra query or join on **every** Basic-authenticated request. A `LazyInitializationException` if the principal is ever built outside the transaction. And a second, competing role system next to the org and project memberships of Phases 3–4. |
| **Trusting the application alone** (no `CHECK`s) | Code paths multiply; the database is the one place every write passes through | A new path (a bulk import, a `psql` fix, a future feature that forgets to normalise) writes `Alice@x.com`. You get duplicate accounts, logins that miss, and a hash no encoder can verify, discovered months later as a data clean-up. |
| **A bare `BCryptPasswordEncoder`** | Stored hashes would carry no marker of which algorithm made them | Changing algorithm or cost means old and new hashes can't be told apart, so you can't verify both side by side. The result is a **forced password reset for every user**, or a risky one-shot migration. |
| **Spring's `JdbcUserDetailsManager`** instead of our own service | It expects Spring's own `users` / `authorities` tables, and returns Spring's `User` | Locked into Spring's schema, with none of our columns (lock, verification, username) and no id in the principal. |
| **The entity implements `UserDetails`** | The security context lives outside any transaction | Once Phase 3 adds associations (memberships), anything that touches them from the principal throws `LazyInitializationException`. The principal is a stale copy of the row, and it could be accidentally merged back. Security methods also clutter the domain entity. |
| **Spring's `User` as the principal** | It has no id and no app username | Every "who is calling" check (`/me`, Phase 4 permissions) needs an **extra query per request** to turn an email into an id. The auditor could only write emails. |
| **`Instant.now()` inside the logic** | "Now" becomes a hidden input no test can control | §1.4 token expiry and §1.5 lockout become testable only by sleeping or tiny durations: slow, flaky tests near the boundary. Bugs like the inverted lock check slip through. |
| **Normalising ad hoc in each caller** | Each caller would implement it slightly differently | Register, resend (§1.4) and reset (§1.6) drift: one forgets `strip()`, another uses the default locale. A registered email then isn't found at login or reset, or two accounts appear for one person. |
| **`authentication.getName()` in the auditor** | It returns the principal's `getUsername()`, which is the email | Email addresses (PII) in `created_by` / `updated_by` of every table, copied into backups and exports. A GDPR erasure request then means scrubbing every audit column, and the audit trail breaks when someone changes their email (Phase 0 decision #9). *(Hit.)* |

### What we optimised for

**Stored secrets that survive a database leak** (slow, salted, upgradeable hashes). **Correctness guarded in depth**: validation, service and schema each catch what the others miss. **Testable time.** **No PII where it doesn't belong** (audit columns, exception messages).

### Concepts

The phase-level concepts above (password checking pipeline, `SecurityContextHolder`, password hashing), plus learning log §4 (§1.2): who calls whom, the `UserDetails` flag mapping, `hasRole` vs `hasAuthority`, bcrypt internals, `Clock`, why the principal lives in `common`.

### Requirements

**Migration `V2__create_user_accounts.sql`** (every constraint named: `pk_`, `uk_`, `ck_`):
- [x] `email varchar(254)`, unique, `CHECK` lowercase, `CHECK` basic format.
- [x] `username varchar(50)`, unique, `CHECK` 3–50 lowercase letters/digits/`._-`, **no `@`** (keeps "log in with either" safe, decision 3).
- [x] `display_name`, `timezone` (default `UTC`).
- [x] `password_hash varchar(200)`, sized for the longest encoder we could migrate to, with a `CHECK` for the `{id}` prefix.
- [x] `role` (decision 5), `email_verified_at`, `failed_login_attempts` (≥ 0), `locked_until`, `password_changed_at`, audit columns.

**Entity, repository, encoder, clock:**
- [x] `UserAccount` extends `BaseEntity`: protected no-arg constructor, `@Enumerated(STRING)`, no public setters on security fields, a factory that sets `role = USER` and `timezone = "UTC"` **in Java** (Hibernate sends every column, so DB defaults don't apply to its inserts).
- [x] `UserAccountRepository.findByEmail`.
- [x] `DelegatingPasswordEncoder` bean.
- [x] `Clock` bean (`Clock.systemUTC()`) in `common/config`.
- [ ] 📊 Time one `encode()` at bcrypt cost 10 and 12.

**Principal, `UserDetailsService`, normalisation, auditor:**
- [x] `Normalize.normalizeEmail`: `strip()` + `toLowerCase(Locale.ROOT)`.
- [x] `TaskflowPrincipal`: id, app username (`getAppUsername()`), email as `getUsername()`, hash, `ROLE_`-prefixed authorities, and **overridden** `isEnabled()` / `isAccountNonLocked()` (the interface's defaults return `true`).
- [x] `TaskflowUserDetailsService`: normalise, load in a `readOnly` transaction, `enabled` = email verified, `accountNonLocked` = unlocked **from** `locked_until` onward (by the `Clock`), exception message without the email.
- [x] Boot's generated password gone from the startup log.
- [x] `AuditAwareImpl`: `TaskflowPrincipal` → app username; anything else → `"system"`.
- [x] Dead `spring.security.user` test config deleted; tests create real users (`TestUsers`).
- [x] No seed users in versioned migrations; first admin by `UPDATE`.

**Moved or decided against:**
- The **72-byte password check** moved to §1.3 (boundary validation belongs on the registration DTO).
- **`CredentialsContainer`** not implemented (decision 12 in the learning log).

**Studied, not built: logging in with email *or* username (decision 3).**
- `loadUserByUsername(String)` gets whatever the user typed; "username" there means *login identifier*.
- Resolve unambiguously: `@` → email, otherwise username. That's only safe because usernames can't contain `@`.
- Count failed logins against the **user row**, never the typed string, or alternating identifiers doubles an attacker's attempts.
- Normalise both identifiers the same way everywhere.

### Traps ⚠️

**Schema and entity:**
- **Two accounts for one person.** `Alice@x.com` and `alice@x.com` register separately, then neither can reset their password. Normalise in **one** place, on **every** path that takes an email, with the DB `CHECK` as the backstop.
- **`toLowerCase()` without a locale** uses the JVM default. Under Turkish, `TITLE@X.COM` becomes `tıtle@x.com`. Always `Locale.ROOT`.
- **`@Enumerated` defaults to `ORDINAL`.** Insert a new role in the middle of the enum and every existing user's role silently shifts. Use `STRING`. *(Mutation-checked: `ddl-auto: validate` refused to start.)*
- **DB defaults don't apply to Hibernate inserts.** `DEFAULT 'USER'` only works when an `INSERT` leaves the column out. Hibernate sends every mapped column, so an unset Java field goes in as `NULL` and fails NOT NULL. Set defaults in Java.
- **Postgres reserves `user`** (so the table is `user_accounts`), and **silently truncates identifiers over 63 characters**, which would break any error mapping by constraint name.
- **Seed users in a `V`-migration** run in production, shipping a known admin password. Create the first admin by hand.

**Passwords:**
- **bcrypt only uses the first 72 *bytes*.** `@Size` counts characters, and an emoji is 4 bytes. *(Handled in §1.3.)*
- **A hash without its `{bcrypt}` prefix** has no encoder mapped to it and can't be verified. *(Now refused at insert by a `CHECK`.)*

**The principal:**
- **`UserDetails`' flag methods have `default` implementations returning `true`** (since Spring Security 6.3). Add `enabled` / `accountNonLocked` fields without overriding `isEnabled()` / `isAccountNonLocked()` and it compiles, and **locked and unverified users log in**. *(Hit.)*
- **`getUsername()` vs the app username.** `getUsername()` must return the **email** (the login identifier). An accessor for the app username needs an unmistakably different name (`getAppUsername()`).
- **A principal's `toString()` ends up in Spring Security's DEBUG log** ("Set SecurityContextHolder to …"). A record's generated `toString()` prints **every** field, the password hash included.
- **No `ROLE_` prefix on the authority** → `hasRole("ADMIN")` refuses a real admin with 403.
- **An inverted boundary.** `!now.isAfter(lockedUntil)` means *during* the lock: locked users could log in, and were locked forever once it expired. *(Hit.)* Decide the boundary once ("unlocked from `locked_until` onward") and test that exact instant.

**The auditor:**
- **`isAuthenticated()` is true for anonymous requests.** `AnonymousAuthenticationToken` reports itself authenticated, so `created_by` becomes `anonymousUser`. Check the principal **type**.
- **`authentication.getName()` returns `getUsername()`, which is the email.** *(Hit: `created_by = alice@example.com` on a real run.)*

**Tests and dev:**
- **Your own `UserDetailsService` makes Boot's generated user disappear**, and with it `spring.security.user` in the test config. Integration tests go **401**. *(Hit; tests now create real users.)*
- **`SecurityContextHolder` is a ThreadLocal.** A test that sets it must clear it in `@AfterEach`, or the next test inherits the user.
- **`psql -c "…"` corrupts bcrypt hashes**: the shell expands `$2y`, `$10` inside double quotes. Paste into interactive `psql`. The table has no id default: use `nextval('global_id_seq')`.
- **Logging in with either identifier** (studied): a username containing `@` can equal someone else's email, and counting failures per typed string doubles an attacker's attempts.

### Deliberate failures

| Create this | What you see | Lesson |
|---|---|---|
| **The auditor using `authentication.getName()`** (done on a real run) | `created_by = alice@example.com` | The principal's `getUsername()` is the login email; audit columns got PII |
| **An authority without `ROLE_`** (mutation-checked) | A real admin → 403 on `/actuator/metrics` | `hasRole` adds the prefix itself |
| **A hash inserted without `{bcrypt}`** | Rejected at insert by `ck_user_accounts_password_hash_prefixed` | The database as the final guard, catching the mistake before it reaches a login |
| **Mutation checks** (7 bugs planted after the tests existed: inverted lock, `getName()` auditor, missing `isEnabled()`, no `ROLE_`, no `Locale.ROOT`, no normalisation, `ORDINAL`) | Each caught; `ORDINAL` before any test ran | Table in learning log §7 |

### Build order (as it happened)

Decision 5 → migration (verified in a rolled-back transaction) → entity + repository → normaliser → principal → encoder + `Clock` beans → `UserDetailsService` → auditor → manual run with SQL-created users (lock, role, auditor) → fix tests → §1.2 tests.

### Tests

`TaskflowUserDetailsServiceTest` (unit, fixed `Clock`, lock boundary at +600/+1/0/−1 s), `AuditAwareImplTest`, `NormalizeTest` (Turkish locale), `UserAccountRepositoryTest` (JPA slice: constraints, role stored as text), plus real-user integration tests (unverified → 401, locked → 401, admin → metrics). 7/7 planted bugs caught.

🎯 **Interview questions:** "Why bcrypt and not SHA-256 for passwords, but SHA-256 and not bcrypt for reset tokens?" · "How would you migrate every user to a new hashing algorithm?" (`upgradeEncoding` + `UserDetailsPasswordService`, rehashing on the next successful login; implementing it is an **optional stretch**.)

---

## 1.3 — Registration ✅ built (tests deferred, see the learning log's debt)

### What we're building

**The first way for a person to create their own account.** `POST /api/v1/auth/register` takes an email, a username, a display name and a password. It stores a new **unverified `USER`** in `user_accounts`, with the password hashed, and answers **201** with a summary of the account.

What it means for the user:
- **They can't log in yet.** §1.2 already made unverified accounts fail authentication (`isEnabled()` is false). §1.4 sends the verification email and adds the endpoint that verifies it. Until then, in dev, you verify by hand with one SQL `UPDATE`.
- **They get clear answers when something's wrong:**
  - an invalid field → **400**, naming the field (never echoing its value)
  - an email already registered → **409 `EMAIL_ALREADY_REGISTERED`** (decision 7)
  - a username already taken → **409 `USERNAME_TAKEN`**

**Out of scope, on purpose:**

| Not in §1.3 | Where it goes |
|---|---|
| Sending the verification email, and the "send after commit" rule | §1.4 (the email sender and tokens are built there, so registration gains that step then) |
| Rate limiting / CAPTCHA against mass sign-ups and email probing | Phase 10 |
| Checking the password against breached-password lists | Interview knowledge only (HIBP k-anonymity) |
| Changing email or username later | Not in Phase 1 |

It's also the **first endpoint where an anonymous caller writes data**, which puts the §1.2 auditor's `"system"` path to real use.

### How we're building it, and why

**The moving parts.** It's the same shape as the organization create flow from Phase 0, with three security additions:

```
POST /api/v1/auth/register  (anonymous, already permitted by the §1.1 rules)
  │
  ▼  AuthController (user package): HTTP only. @Valid binds + validates RegisterRequest.
  │     invalid → MethodArgumentNotValidException → Phase 0 handler → 400 with fieldErrors
  ▼  RegistrationService.register(...)   @Transactional
  │     1. normalise email + username (lowercase, trimmed); strip display name
  │     2. email taken?    → 409 EMAIL_ALREADY_REGISTERED   (checked first)
  │     3. username taken? → 409 USERNAME_TAKEN
  │     4. hash the password (DelegatingPasswordEncoder → {bcrypt}…)
  │     5. UserAccount.createUserAccount(…, passwordChangedAt = now from the Clock)
  │     6. saveAndFlush, translating a unique-constraint violation (the race) into the SAME two codes
  │     7. map to the response record; log the new id (never the email)
  ▼  201 + { id, email, username, displayName, emailVerified: false, createdAt }
```

**The key choices:**

| Choice | Problem it solves / avoids |
|---|---|
| **409 `EMAIL_ALREADY_REGISTERED`** (decision 7) | A person who forgot they have an account gets a clear answer and goes to login or reset. **Knowingly accepted:** this endpoint reveals which emails are registered. Login and reset must still never leak (§1.5, §1.6), and Phase 10's rate limiting blunts mass probing. |
| **Validate before hashing** | bcrypt is deliberately slow; an invalid request shouldn't cost a hash |
| **Minimum length + at most 72 UTF-8 *bytes*, no composition rules** | Length is what makes passwords strong (NIST SP 800-63B), and the byte cap matches bcrypt's real limit, so a long password is a clean **400** |
| **A custom `@MaxUtf8Bytes(72)` constraint** | Bean Validation has no byte-length constraint; this keeps the rule at the boundary with the other field rules, reusable in §1.6 |
| **The request record masks the password in `toString()`** | A record's generated `toString()` would print it |
| **Email *and* username normalised in the service** | `Alice` / `alice` can't become two accounts, and a registered email always matches what login looks up |
| **Service check first, then `saveAndFlush` with constraint translation** | The race gets the **same specific 409** as the normal path |
| **Translation in `user`; only the "read the constraint name" helper in `common`** | `common` never learns about user tables |
| **201 with the account in the body, no `Location`** | No URL is advertised that the caller can't fetch |
| **The response exposes id, email, username, display name, `emailVerified`, `createdAt` only** | The hash, lock state and failed-attempt count never leave the server |
| **The 409 names the `field`, not the submitted value** | Clients can highlight the right field; the email isn't copied into error bodies |

#### Alternatives we didn't take

| Alternative | Why we didn't take it | The problem it would cause later |
|---|---|---|
| **Always 202**, and email the existing owner "someone tried to register as you" | Worse experience for honest users, and it needs the §1.4 email sender before registration can exist | A user who mistypes can't tell whether sign-up worked; support requests and extra email templates follow. Rate limiting is still needed anyway. It would be the right call if emails were sensitive, e.g. a service where membership itself is private. |
| **Validating inside the service**, after work has started | The request would already cost a bcrypt hash | Every malformed request burns ~100 ms of CPU: a cheap CPU-exhaustion attack on sign-up. Validation logic is also spread between layers. |
| **Composition rules** ("1 uppercase + 1 symbol") | NIST advises against them: they produce predictable passwords | Users pick `Password1!` and write it down. Security gets worse, and so does support volume. |
| **`@Size(max = 72)`** | It counts characters, not bytes | A user with a long non-ASCII passphrase gets a **500**. On versions that truncate instead, two different passwords sharing the first 72 bytes **both log in**. |
| **Checking the byte length in the service** | The rule would live apart from the other field rules | §1.6's reset and change requests need it too, so it gets duplicated, and it returns a different error shape from ordinary field validation. |
| **Relying on nobody logging the request** | Nothing would stop it | During an incident someone adds debug logging, and plaintext passwords go into log aggregation: shared, retained, searchable. The fix becomes a **credential reset for every affected user**. |
| **Rejecting uppercase usernames at the boundary** | Unfriendly, and a common source of failed sign-ups | Mobile keyboards auto-capitalise `Alice`, which gets a confusing 400. |
| **Normalising in the controller** | It's a business rule, not HTTP | Any other way into the service (an admin tool, an import, a test calling the service) skips it, and `Alice` becomes a second account. |
| **Plain `save()`** | The INSERT runs at commit, outside the `try` | The race returns a *different* code (`RESOURCE_CONFLICT`) from the normal path, so clients that handle `EMAIL_ALREADY_REGISTERED` break **intermittently**, and the constraint name leaks. |
| **A constraint → error-code registry in `common`** | `common` would have to know every feature's constraints | Each new feature edits `common`, and the Phase 0 dependency rule erodes until `common` imports the whole app. |
| **`Location: /api/v1/users/{id}`** | That URL doesn't exist, and users can't read each other | Clients follow it and get 404/403, which looks like a bug. Worse, someone later adds `GET /users/{id}` "to make `Location` work", opening **lookup of any user by id**. |
| **200 instead of 201** | It breaks the convention every other create in the API follows | Clients can't rely on status codes to tell "created" from "OK", and the API gets inconsistent. |
| **Returning the entity** | Serialises every field | The password hash, lock state and failed attempts go to clients, and any field added to the entity later is **published silently**. |
| **Echoing the email in the 409**, like the organization slug | The email is personal data | Emails copied into error bodies end up in client logs, proxies and error trackers: PII spreads where it can't be deleted. |

### What we're optimising for

1. **One consistent error contract, even under races**: the same 409 code whether the service check or the database constraint catches the duplicate.
2. **The password never escapes**: not in logs, not in error bodies, not in responses, and never stored in plain form.
3. **Cheap rejection before expensive work**: validation before bcrypt.
4. **A deliberate, written-down enumeration trade-off**, rather than an accidental one.

### Concepts

💡 **A custom Bean Validation constraint.** Two pieces: an **annotation** (`@MaxUtf8Bytes(72)`, marked `@Constraint(validatedBy = …)`) and a **`ConstraintValidator<MaxUtf8Bytes, String>`** whose `isValid` counts `value.getBytes(StandardCharsets.UTF_8).length`. Hibernate Validator finds the validator through the annotation, with no registration needed. By convention **`null` is valid**: presence is `@NotBlank`'s job, so each constraint checks one thing and error messages don't double up.

💡 **Where boundary validation goes after `@Valid`.** A failing `@Valid @RequestBody` throws `MethodArgumentNotValidException` before your controller method runs. Your Phase 0 `GlobalExceptionHandler` turns it into a **400** `ProblemDetail` with `fieldErrors` (field + message, **never the rejected value**). That's why the password is safe in validation errors: the handler never echoes values. §1.3 adds a test that locks that in.

💡 **When the INSERT actually happens, and why `saveAndFlush`.** `save()` only puts the entity into the persistence context; the SQL `INSERT` runs at **flush**, normally at commit, which happens **after your service method returns**. So a unique-constraint violation from the race escapes any `try/catch` inside the method, and reaches the global handler as a generic 409. `saveAndFlush()` forces the INSERT **inside** your `try`, so the feature can translate it. The exception arrives as Spring's `DataIntegrityViolationException` (translated at the repository proxy, testing guide §6.7), with Hibernate's `ConstraintViolationException` somewhere in its cause chain carrying the constraint name.

⚠️ **After a failed flush, the transaction is finished.** The persistence context is broken and the transaction is marked rollback-only. Translate the exception and **throw**; never catch it and carry on in the same transaction.

💡 **Account enumeration, applied to registration** (decision 7). The 409 tells whoever asks that an email is registered. Most products accept that on sign-up, because the alternative costs usability and the real defence is rate limiting. What you *can't* accept is the same leak on login or password reset, where hiding it is cheap. Know both halves of this answer.

💡 **Password policy, the modern version** (NIST SP 800-63B): require **length**; don't force composition rules; don't force periodic changes; ideally check against breached-password lists. Recent NIST guidance sets a 15-character minimum for password-only authentication; 8 is the traditional floor. Pick yours, and write one line on why.

### Requirements

**Decisions for this section:**
- [x] **Decision 7: duplicate email → 409 `EMAIL_ALREADY_REGISTERED`** (recorded 2026-09-25).
- [x] 🏗️ **Password minimum length: 12 characters** (decided 2026-09-25). Between the traditional floor of 8 and NIST's newer 15 for password-only login, and short enough that people won't write it down.
- [x] 🏗️ **Username case: accept any case, lowercase it in the service** (decided 2026-09-25), exactly like the email. Consequences: the boundary `@Pattern` must allow uppercase (or `Alice` is rejected before the service ever sees it), the same `strip()` + `toLowerCase(Locale.ROOT)` rule applies (add a `normalizeUsername` next to `normalizeEmail`), and the DB `CHECK` guards the stored form.

**Request: `RegisterRequest` record** (`user` package):
- [x] `email`: `@NotBlank`, `@Email`, `@Size(max = 254)`.
- [x] `username`: `@NotBlank`, `@Size(min = 3, max = 50)`, `@Pattern` matching the DB rule (letters, digits, `._-` inside, starting and ending with a letter or digit, no `@`), case-insensitive per your decision.
- [x] `displayName`: `@NotBlank`, `@Size(max = 100)`.
- [x] `password`: `@NotBlank`, `@Size(min = …)` per your decision, **`@MaxUtf8Bytes(72)`**.
- [x] `toString()` overridden: the password never appears.

**Constraint:**
- [x] `@MaxUtf8Bytes` annotation + validator in `common` (reusable for §1.6's reset and change requests). `null` counts as valid.
- [x] 🔍 **Find out what your encoder does with 73 bytes**: one unit test calling `encode()` with a 73-byte password. Recent Spring Security versions throw rather than truncate. Record the answer.

**Response: `UserAccountResponse` record** (or similar):
- [x] `id`, `email`, `username`, `displayName`, `emailVerified` (boolean), `createdAt`, with a static `from(UserAccount)`. Nothing else.

**Errors: `UserErrorCode` enum** (`user` package, implements `ErrorCode`):
- [x] `EMAIL_ALREADY_REGISTERED` (409), `USERNAME_TAKEN` (409).
- [x] Thrown as the existing `ResourceConflictException`, with property `field` = `"email"` / `"username"`, and a `detail` that **doesn't contain the submitted value**.

| Situation | Status | `code` |
|---|---|---|
| Missing or invalid field(s) | 400 | `VALIDATION_FAILED`, with `fieldErrors` naming each field |
| Email already registered (after normalisation) | 409 | `EMAIL_ALREADY_REGISTERED` |
| Username already taken (after normalisation) | 409 | `USERNAME_TAKEN` |
| Both taken | 409 | `EMAIL_ALREADY_REGISTERED` (email is checked first) |
| The same duplicates, lost in a race and caught by the DB | 409 | **the same two codes** |
| Success | 201 | the account summary |

**Service: `RegistrationService`** (`user` package):
- [x] `@Transactional`. Normalise email and username; strip the display name.
- [x] `existsByEmail` → 409; `existsByUsername` → 409 (add both to the repository).
- [x] Hash with the `PasswordEncoder` bean; `passwordChangedAt` from the `Clock`.
- [x] `saveAndFlush` inside a `try`. On `DataIntegrityViolationException`, read the constraint name: `uk_user_accounts_email` → `EMAIL_ALREADY_REGISTERED`, `uk_user_accounts_username` → `USERNAME_TAKEN`, anything else → rethrow unchanged.
- [x] Move the "find the constraint name in the cause chain" helper out of `GlobalExceptionHandler` into a small `common/error` utility, and use it from both places.
- [x] Log at INFO: `Registered user account id={}`. Never the email, username or password.

**Controller: `AuthController`** (`user` package, `@RequestMapping("/api/v1/auth")`; §1.4–§1.6 add their endpoints here):
- [x] `POST /register` → `@Valid @RequestBody RegisterRequest` → service → **201** with the body, no `Location`.

### Traps ⚠️

- **Records print their secrets.** A record's generated `toString()` includes **every** component. One `log.debug("Registering {}", request)` puts a plaintext password in the logs. Override `toString()` in every request record that holds a secret (password now, tokens in §1.4).
- **`@Size(max = 72)` doesn't protect bcrypt.** It counts characters; bcrypt's limit is 72 **bytes**. 19 emoji pass `@Size` and are 76 bytes.
- **`save()` inside a `try` catches nothing.** The INSERT runs at flush or commit, **after your method returns**, so the race's constraint violation escapes your `catch` and reaches the global handler as a generic 409. Use `saveAndFlush()` inside the `try`.
- **After a failed flush, the transaction is dead.** The persistence context is broken and the transaction is rollback-only. Translate and **throw**; never catch and carry on.
- **Two unique constraints, one generic code.** Without translation, the race gives `RESOURCE_CONFLICT` plus a `constraint` property that exposes `uk_user_accounts_email`. The client can't tell email from username, and your schema leaks.
- **The username's case, checked in the wrong place.** If you lowercase usernames in the service but the boundary `@Pattern` only allows lowercase, `Alice` is rejected with a 400 before the service ever normalises it. The boundary pattern must allow uppercase.
- **Echoing input in errors.** Validation errors must never include the rejected value (the password is in the body). And don't copy the submitted email into the 409 body: name the field instead.
- **Logging PII.** Log the new account's **id**, never its email or username.
- **Hashing before validating** spends ~100 ms of CPU per garbage request. Validate first.
- **`common` learning about user tables.** Only the generic "read the constraint name" helper moves to `common`; the map from `uk_user_accounts_*` to error codes stays in `user`.
- **Reusing registration's enumeration leak elsewhere.** Decision 7 accepts it *here*. Login (§1.5) and reset (§1.6) must still give identical responses for known and unknown emails.
- **A `Location` header for a resource the caller can't fetch.** There's no `GET /users/{id}`, so don't invent one.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| **Log the `RegisterRequest` before masking `toString()`** (`log.info("{}", request)`), then register | Your plaintext password in the app log | Records print everything. Then add the mask, and watch the same line show `****`. |
| **Use `@Size(max = 72)` instead of `@MaxUtf8Bytes`**, and register with 19 emoji (76 bytes) | Whatever your encoder does past 72 bytes (find out: an exception → **500**, or silent truncation) | Characters aren't bytes. Then switch to `@MaxUtf8Bytes` → a clean **400**. |
| **Race it with `save()` instead of `saveAndFlush()`**: 20 concurrent registrations, same email | A mix of `EMAIL_ALREADY_REGISTERED` (caught by the service check) and **generic `RESOURCE_CONFLICT`** with the constraint name (caught at commit, outside your `try`) | Flush timing, made visible. Switch to `saveAndFlush` → exactly one 201, every other response `EMAIL_ALREADY_REGISTERED`, zero 500s. |
| **Mutation checks** once the tests exist | Each planted bug turns a test red | E.g. remove the password mask, drop username normalisation, swap the email/username check order, map a race to the wrong code |

**The race command** (deliberate failure #3): 20 simultaneous registrations, same email, different usernames. It counts how each one ended:

```bash
seq 20 | xargs -P 20 -I{} curl -s -H 'Content-Type: application/json' -d '{"email":"race1@example.com","username":"racer{}","displayName":"Racer","password":"race-password-1"}' localhost:8080/api/v1/auth/register | grep -o '"code":"[A-Z_]*"\|"id":[0-9]*' | sed -E 's/"id":[0-9]+/CREATED/' | sort | uniq -c
```

Run it once with `save()` (restart first), once with `saveAndFlush()` using `race2@…`, then `delete from user_accounts where email like 'race%@example.com';`

### Build order

1. **Decide** the password minimum and username case (above).
2. **`UserErrorCode`**: the two codes.
3. **`@MaxUtf8Bytes` + validator**, and its unit tests. It's pure Java, the easiest place to start. Add the 73-byte encoder check while you're there.
4. **`RegisterRequest`** with its constraints. ⚠️ **Before masking `toString()`**, do the first deliberate failure (log the request, see the password), then mask it.
5. **`UserAccountResponse`** with `from(UserAccount)`.
6. **Constraint-name helper** moved to `common/error`; `GlobalExceptionHandler` uses it (its tests must stay green).
7. **`RegistrationService`**: normalise → email check → username check → hash → create → `saveAndFlush` with translation → map → log.
8. **`AuthController`** `POST /register`.
9. **Run it by hand** (below).
10. **Tests**, then the 📊 **race deliberate failure** (`save` first, then `saveAndFlush`), then mutation checks.

**The manual run** (app running, anonymous `curl`):
- Register a new account → **201**. In `psql`: email and username lowercase, hash starts with `{bcrypt}`, `email_verified_at` null, `created_by = 'system'`.
- Same email in different case → **409 `EMAIL_ALREADY_REGISTERED`**. Same username, new email → **409 `USERNAME_TAKEN`**.
- Empty body, a bad email, a 7-character password, a 73-byte password (e.g. 19 emoji) → **400** with `fieldErrors`, and the password **nowhere** in the response. (Do the 19-emoji request once with `@Size(max = 72)` first: that's the second deliberate failure.)
- Basic login as the new user → **401** (unverified). Then `update user_accounts set email_verified_at = now() where email = '…';` → login **200**.

### Tests

| Kind | Must prove |
|---|---|
| **Unit** | `@MaxUtf8Bytes`: 72 ASCII bytes pass, 73 fail, an emoji counts as 4 bytes, `null` passes · `RegisterRequest.toString()` never contains the password · `RegistrationService` (Mockito + fixed `Clock`): normalises **before** checking; email taken → `EMAIL_ALREADY_REGISTERED` and **nothing saved**; username taken → `USERNAME_TAKEN`; the stored hash is the encoder's output, never the raw password; `passwordChangedAt` = the clock's instant; a `DataIntegrityViolationException` naming `uk_user_accounts_email` becomes `EMAIL_ALREADY_REGISTERED`, and an unrelated constraint is rethrown |
| **Web slice** (`@WebMvcTest(AuthController)` + security config) | Valid body anonymously → **201** + body · invalid body → **400**, `code = VALIDATION_FAILED`, `fieldErrors` for each bad field · **the submitted password never appears in the response body** (the Phase 0 `rejectedValue` debt, closed) · 73-byte password → 400 |
| **Integration** | Register → 201 → row stored as described in the manual run · duplicate email in different case → 409 + code · duplicate username → 409 + code · new account → Basic login 401 until verified |
| 📊 **Race** | 20 concurrent registrations with the same email: **exactly one 201**, the rest **409 `EMAIL_ALREADY_REGISTERED`**, **zero 500s**, and no generic `RESOURCE_CONFLICT`. Then swap `saveAndFlush` for `save` and watch the generic code appear: that's the flush-timing lesson, made visible. |

🎯 **Interview questions:**
- "Does your registration endpoint leak which emails have accounts? Should it?" (Yes, knowingly: decision 7; login and reset don't.)
- "Two users register the same email at the same instant. What happens?" (The check → the constraint → the same 409, thanks to `saveAndFlush`.)
- "Why can't you just use `@Size(max = 72)` for a bcrypt password?"
- "How would a password end up in your logs, and how did you prevent it?"
- "Why does registration return 201 without a `Location` header?"

---

## 1.4 — Email verification (~2h, tests included)

### What we're building

**Proof that a person owns the email address they registered with.** Today (§1.3) anyone can register as `ceo@yourcompany.com`, and the account just sits there unverified, unable to log in. §1.4 closes the loop:

1. **Registration sends an email** containing a one-time link.
2. The link opens a **frontend page**, which sends the token to **`POST /api/v1/auth/verify-email`**.
3. The account becomes **verified**, and from then on it can log in (§1.2 already checks `isEnabled()`).
4. Lost or expired link? **`POST /api/v1/auth/verify-email/resend`** issues a fresh one, and always answers **202** without saying whether the email exists.

In dev, "sending an email" means **writing it to the log**. You copy the link from there. A real mail server is Phase 9.

**The token machinery built here is reused in §1.6** (password reset), where getting it wrong means account takeover. That's why the token design is deliberately strict even though a verification link feels harmless.

**Out of scope, on purpose:**

| Not in §1.4 | Where it goes |
|---|---|
| Real SMTP, HTML emails, sending asynchronously | Phase 9 |
| A scheduled job deleting expired tokens | Phase 9 (the `@Scheduled` job candidate) |
| Rate limiting resend (stopping email-bombing) | Phase 10 |
| Recording an `EMAIL_VERIFIED` security event | §1.5, when the `security_events` table exists; §1.5 adds the call to verification |
| The frontend page itself | Not ours; the API only defines the link format |

### How we're building it

```
REGISTER  (§1.3, extended)
  AuthController ─▶ RegistrationWorkflow.register(…)          ← NOT transactional
                      ├─ 1. UserRegistrationService.register(…)  @Transactional
                      │       create the account (as today)
                      │       + VerificationTokenService.issue(account, EMAIL_VERIFICATION)
                      │            raw = 32 random bytes, Base64URL        (only ever in memory + the email)
                      │            store SHA-256(raw) as hex, expires_at = now + 24h
                      │            delete this user's older unused tokens of the same purpose
                      │       returns (account, raw token)  ── COMMIT ──
                      └─ 2. EmailSender.send(link with the raw token)     ← only after the commit succeeded
                             dev: LoggingEmailSender writes it to the log
                             tests: a capturing fake · prod: none yet → the app refuses to start

VERIFY    POST /api/v1/auth/verify-email  { "token": "…" }
  VerificationService @Transactional
     one conditional UPDATE: set used_at = now
        WHERE token_hash = sha256(token) AND purpose = EMAIL_VERIFICATION
          AND used_at IS NULL AND expires_at > now          ← now from the Clock, passed in
     1 row → mark the account verified → 204
     0 rows → 400 TOKEN_INVALID  (unknown, expired, already used, wrong purpose: one answer)

RESEND    POST /api/v1/auth/verify-email/resend  { "email": "…" }
  → always 202. Only if the account exists AND is unverified: issue a new token, send after commit.
```

**The link in the email:** `{frontend-base-url}/verify-email#token=<raw>`. The token is in the URL **fragment** (after `#`), which browsers never send to any server.

#### The choices

| Choice | Problem it solves / avoids |
|---|---|
| **32 bytes from `SecureRandom`, Base64URL without padding** | 256 bits nobody can guess or predict, safe to put in a URL (no `+`, `/` or `=`) |
| **Store only the SHA-256 of the token** (hex), with a `CHECK` that the column looks like a hash | A leaked database, backup or read replica holds no working links. The `CHECK` stops raw tokens being stored by mistake. |
| **SHA-256, not bcrypt** | The token already has 256 bits of entropy, so a fast hash is safe. And we must **find the row by its hash**, which a salted hash can't do. |
| **Expiring, single-use, purpose-bound** | A stolen old link is useless; a verification token can never reset a password (§1.6) |
| **Issuing a new token deletes the old unused ones** (same user, same purpose) | Only the latest link works; the table doesn't grow per resend |
| **Consume with one conditional `UPDATE` and check the row count** | Two clicks at the same instant can't both succeed. Harmless for verification, but in §1.6 it would mean one reset link used twice. |
| **"Now" from the `Clock`, passed into the `UPDATE`** | One time source, so expiry is testable with a fixed clock |
| **One error for every bad token: `400 TOKEN_INVALID`** | The client's next step is the same in every case (ask for a new link), and no hint of which tokens exist |
| **The token travels in a POST body; the email link carries it in the fragment** | Mail scanners that pre-open links can't consume it (consuming needs a POST); it never appears in server logs, `Referer` headers or analytics |
| **Send only after the transaction commits** (a separate, non-transactional `RegistrationWorkflow` bean) | No email for an account that was rolled back, and no database transaction held open while mail is sent |
| **If sending fails after commit: log it, still answer 201** | The account exists; resend is the recovery path. Nothing is half-done in the database. |
| **Resend always answers 202** | Resend doesn't reveal which emails are registered, or which are verified |
| **`EmailSender` interface in `common`; the logging implementation active only in `dev`** | Features depend on "send an email", not on how. Phase 9 adds SMTP without touching them. |
| **No `EmailSender` for `prod` yet, so a prod start fails** | Fail loudly (the Phase 0 principle): a deploy can't silently lose every verification email |
| **A capturing fake `EmailSender` in the shared test configuration** | Integration tests read the raw token exactly as a user would, from "their inbox". Adding it to the *shared* config keeps one application context. |
| **Token lifetime as typed config** (`taskflow.security.tokens.email-verification-ttl: 24h`, a validated `Duration`) | Changeable per environment, checked at startup, and it carries its unit |
| **`user_tokens` with FK `user_account_id … ON DELETE CASCADE`, plus an index on it** | Deleting a user (a future GDPR erasure) removes their tokens instead of failing, and the lookups by user don't scan the table |

#### Alternatives we didn't take

| Alternative | Why we didn't take it | The problem it would cause later |
|---|---|---|
| **A UUID as the token** | "UUID" promises uniqueness, not unpredictability; only v4 is random, with 122 bits | Someone "upgrades" to **UUID v7** (time-ordered, popular for keys): tokens become guessable from their creation time, and in §1.6 that's **account takeover** |
| **`java.util.Random`** | It's predictable: its output follows from a 48-bit seed | An attacker who sees a few tokens can compute the next ones, so every §1.6 reset link is forgeable |
| **Storing the raw token** | Whoever reads the table gets working links | A leaked backup, a read replica, or a support engineer with DB access can verify, or in §1.6 **reset the password of**, any account |
| **bcrypt for the token hash** | Salted, so you can't look a row up by hash | Every verify must load *all* unused tokens and bcrypt-compare each (~100 ms apiece): the endpoint becomes a CPU-exhaustion attack |
| **`GET /verify-email?token=…` on the API** | GET is supposed to change nothing, and scanners pre-open links | Corporate mail scanners "click" the link first and **consume the token**, so the real user sees "invalid link". The token also lands in access logs and proxy logs. |
| **The token in the frontend link's query string** (`?token=`) | Query strings are sent to servers and leak via `Referer` | Every script on that page (analytics, a CDN) can receive the URL. Fine-sounding for verification; for §1.6 reset links it's a takeover path. |
| **Different errors for expired / used / unknown** | No client benefit: the next step is the same | A small oracle revealing which tokens exist or were used, and more client branches to maintain |
| **Find the token, check it, then save** (check-then-act) | Two concurrent requests both pass the check | In §1.6, one reset link applied **twice**, possibly with two different new passwords |
| **The database's `now()` in the `UPDATE`** | Two time sources | Tests with a fixed `Clock` no longer control expiry; boundary bugs (like §1.2's inverted lock) slip through |
| **Marking old tokens "revoked"** instead of deleting them | Needs a `revoked_at` column, and every query must filter on it | The table grows with every resend. History of security actions belongs in §1.5's `security_events`, not here. |
| **Sending the email inside the transaction** | If the commit then fails, the email is already gone | A **phantom email**: a link to an account that doesn't exist. With Phase 9's real SMTP (seconds per send), the transaction and its DB connection stay open that long, exhausting the connection pool under load. |
| **`TransactionSynchronization.afterCommit` inside one method** | Works, but the "send later" is hidden inside a callback | Harder to read and test. Phase 9 introduces events for this, and we'd end up with two styles. |
| **`@TransactionalEventListener(AFTER_COMMIT)` now** | That's Phase 9's topic, with async and its own traps | Pulls Phase 9 forward and mixes two lessons |
| **Rolling back the registration if the email fails** | The send happens after commit; there's nothing to roll back | Rolling back would need the email *inside* the transaction, which brings back the phantom email |
| **The logging sender active in every profile** | Tokens in logs are acceptable only on a developer's machine | In production, anyone with log access can verify accounts, and in §1.6 **reset any password** |
| **A do-nothing sender in `prod`** | The app would start and look healthy | No user can ever verify: a **silent outage** reported by users, not by the deploy |
| **Resend returning 404 / 409** for unknown / already verified | It would reveal which emails exist | A second enumeration oracle, and one without the excuse registration has |
| **A scheduled cleanup job now** | Phase 9's topic, and unnecessary at this scale | — |
| **No `ON DELETE` rule on the FK** | The default refuses to delete a user who has tokens | The first account deletion (e.g. a GDPR erasure) fails with an FK violation |
| **No index on `user_account_id`** | Postgres does **not** index foreign-key columns | "Delete this user's old tokens" and the cascade scan the whole table as it grows |

### What we're optimising for

1. **Tokens that are useless to anyone who reads the database or the logs**: hashed at rest, never in URLs sent to servers, never logged outside dev.
2. **Exactly-once use, even under concurrency**, because §1.6 reuses this for password reset.
3. **No phantom emails, and no transaction held open for mail.**
4. **No new enumeration oracle** (resend is silent).
5. **Testable time and a testable inbox** (fixed `Clock`, capturing fake).
6. **Loud failure in prod** rather than silently lost email.

### Concepts

💡 **Secure token design: the six properties.** Unguessable (256 bits, `SecureRandom`) · hashed at rest · expiring · single-use · purpose-bound · invalidated on reissue. Plus: kept out of URLs sent to servers, and out of logs. This is the interview answer to "design a password-reset token", and §1.4 builds all of it.

💡 **Why a fast hash is right here and wrong for passwords.** Slow hashing defends *low-entropy* secrets (human passwords) against guessing. A 256-bit random token can't be guessed at any speed, so SHA-256 is enough, and it's deterministic, so the database can look the row up by it. Passwords are the opposite on both counts.

💡 **A conditional `UPDATE` as a concurrency gate.** `UPDATE … WHERE … AND used_at IS NULL` is atomic in Postgres: of two concurrent statements, exactly one sees `used_at IS NULL` and updates the row; the other updates **0 rows**. The row count *is* the answer. It's the same idea as Phase 0's unique constraint (let the database arbitrate), with a different mechanism. In Spring Data this is a `@Modifying @Query` returning an `int`.

⚠️ **`@Modifying` queries bypass the persistence context and JPA auditing.** A bulk `UPDATE` / `DELETE` goes straight to SQL: entities already loaded in the same transaction keep their old values (use `clearAutomatically = true`, or don't reuse them), and `@LastModifiedDate` doesn't fire, so `updated_at` won't move. That's acceptable here; know it. (It's a preview of the deferred bulk-operations topic.)

💡 **Transaction boundaries and side effects.** A database transaction can be undone; an email can't. Anything irreversible (email, HTTP calls, messages) goes **after** the commit. The simplest correct structure: a non-transactional method calls a transactional method **on another bean** (a call within the same class bypasses the `@Transactional` proxy, the Phase 0 self-invocation trap), then does the side effect.

💡 **URL fragments.** Everything after `#` stays in the browser. It isn't sent in the request, so it isn't in server or proxy logs and isn't in the `Referer` header. The frontend's JavaScript reads it and POSTs the token.

💡 **Profile-specific beans, and failing fast.** `@Profile("dev")` puts the logging sender only in dev. With no `EmailSender` in prod, Spring can't satisfy the dependency and **refuses to start**. That's a feature. Tests need their own implementation, or every test context fails.

💡 **`@ManyToOne` defaults to `EAGER`.** If `UserToken` references `UserAccount`, mark it `fetch = LAZY`. Otherwise loading any token also loads its user, every time. It's a quiet N+1 seed for Phase 8.

### Decisions for this section: all six recommendations accepted (2026-09-26)

| # | Decision | Chosen |
|---|---|---|
| a | Where the token goes in the email link | **URL fragment** (`#token=`), not the query string |
| b | Old unused tokens when a new one is issued | **Delete them** (not mark as revoked) |
| c | How "send after commit" is structured | **A separate non-transactional `RegistrationWorkflow` bean** calling the transactional service, then the sender |
| d | Email verification token lifetime | **24 hours** (long enough for "I'll do it tonight"; the link is single-use and purpose-bound) |
| e | Email send fails after commit | **Log at ERROR (the account id, never the token), still return 201**; resend is the recovery |
| f | `UserToken` → `UserAccount` | **`@ManyToOne(fetch = LAZY)`** to the entity (vs a plain `Long` id column) |

### Requirements

**Configuration:**
- [ ] Typed properties (a record, like `ApiProperties`): `TokenProperties` (`Duration`, `@NotNull`) and `taskflow.app.frontend-base-url` (`@NotBlank`). Validated at startup.

**Email sending** (`common/mail`):
- [ ] `EmailSender` interface: send one message (to, subject, body). A small `EmailMessage` record.
- [ ] `LoggingEmailSender`, **`@Profile("dev")` only**: logs recipient, subject and body at INFO. ⚠️ A **deliberate, profile-bounded exception** to "no tokens in logs": write that down in a comment.
- [ ] No production implementation yet; a prod start must fail.
- [ ] In tests: a capturing `EmailSender` (keeps sent messages in memory, with a way to read the latest one for an address and to clear them), registered in the **shared** `TestcontainersConfiguration`.

**Migration `V3__create_user_tokens.sql`:**
- [ ] `id`, `user_account_id` (FK to `user_accounts`, **`ON DELETE CASCADE`**), `purpose` (`CHECK` in `EMAIL_VERIFICATION`, `PASSWORD_RESET`), `token_hash` (**unique**, and a `CHECK` that it's exactly 64 lowercase hex characters), `expires_at`, `used_at` (nullable), audit columns.
- [ ] **`ix_user_tokens_user_account_id`**: Postgres won't create it for you. 📌 From now on, every FK gets its index.

**Tokens** (`user` package):
- [ ] `TokenPurpose` enum; `UserToken` entity (`@Enumerated(STRING)`, `@ManyToOne(fetch = LAZY)`, protected constructor, a factory).
- [ ] Token generation and hashing in one small class: 32 bytes from **one shared `SecureRandom`**, Base64URL **without padding** → the raw token; SHA-256 of its UTF-8 bytes → **lowercase hex**.
- [ ] `VerificationTokenService` (or `UserTokenService`, since §1.6 reuses it):
  - `issue(account, purpose)`: delete that user's unused tokens of the purpose, save the new hash with `expires_at = now + ttl`, **return the raw token** (the only place it exists).
  - `consume(rawToken, purpose)`: hash it, run the conditional `UPDATE` with `now` from the `Clock`; 1 row → return the account id; 0 rows → `400 TOKEN_INVALID`.
- [ ] Repository: the conditional `UPDATE` and the `DELETE` as `@Modifying` queries returning a row count.
- [ ] `TOKEN_INVALID` (400) added to `UserErrorCode`.

**Registration, extended:**
- [ ] `UserRegistrationService.register` also issues the token, in the **same** transaction, and returns the account *and* the raw token.
- [ ] New `RegistrationWorkflow` (not transactional, **a separate bean**): calls the service, **then** sends the email. If sending throws: log at ERROR with the account id, still return normally.
- [ ] `AuthController` calls the workflow. Still 201 with the same body; the token **never** appears in the response.

**Verify and resend:**
- [ ] `POST /api/v1/auth/verify-email` `{token}` → **204**; invalid → **400 `TOKEN_INVALID`**. Request record with `@NotBlank` and a **masked `toString()`**.
- [ ] Verifying sets `email_verified_at` (from the `Clock`) through `markEmailVerified`.
- [ ] `POST /api/v1/auth/verify-email/resend` `{email}` → **always 202**. Only for an existing, unverified account: issue a new token and send after commit (through the workflow). Normalise the email.
- [ ] Both endpoints are already permitted anonymously by the §1.1 rules; nothing to change there.

### Traps ⚠️

- **`java.util.Random` or `Math.random()` for tokens.** Predictable. Only `SecureRandom`.
- **Standard Base64 in a URL.** `+`, `/` and `=` get mangled or need escaping. Use the URL-safe encoder, without padding.
- **Hashing inconsistently.** Hash the same bytes (UTF-8 of the raw token) and store the same form (lowercase hex) everywhere, or valid tokens never match. The hex `CHECK` catches a raw token stored by mistake.
- **Check-then-act consumption.** A read followed by a save lets two requests both succeed. Only the conditional `UPDATE` is safe.
- **Two clocks.** Using the DB's `now()` in the `UPDATE` while `expires_at` came from the `Clock`. Pass one instant in.
- **Strict vs inclusive expiry.** Decide whether a token is valid *at* `expires_at` (`>` vs `>=`) and test that exact instant, as with the §1.2 lock.
- **`@Modifying` without awareness.** The bulk `UPDATE` / `DELETE` bypasses the persistence context (stale entities in the same transaction) and JPA auditing (`updated_at` doesn't move).
- **Self-invocation.** Calling the transactional `register` from the same class as the send means **no transaction at all**. The workflow must be a different bean.
- **Sending inside the transaction.** A phantom email when the commit fails, and a transaction held open during mail.
- **The token in a GET query string.** Pre-opening scanners consume it; logs and `Referer` keep it.
- **A record's `toString()` again.** `VerifyEmailRequest` holds the token; `EmailMessage` holds a body with the token. Mask what could be logged outside the dev sender.
- **A missing `@Profile("dev")`** on the logging sender puts tokens in production logs.
- **No `EmailSender` in tests.** Every `@SpringBootTest` context fails to start. Add the fake to the **shared** `TestcontainersConfiguration`: a separate test config would start a second application context and container.
- **Resend revealing existence** through its status, body **or timing**. A known, unverified email does a DB write and a send; an unknown one returns immediately. Note the timing leak; Phase 9's async sending removes most of it.
- **Resend as an email cannon.** Without rate limiting, anyone can flood an inbox. Phase 10; note it.
- **`@ManyToOne` defaulting to `EAGER`.**
- **FK without an index**, and an FK without an `ON DELETE` rule.
- **Returning the raw token anywhere but the email**: the register response, a log line, an exception message.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| **The phantom email.** Temporarily send the email *inside* the transactional `register`, then force the commit to fail (e.g. a `throw new IllegalStateException()` after the send, or a duplicate registration in a race) | The verification email for an account that **doesn't exist** in `user_accounts` | Side effects after commit. Then move the send to the workflow and repeat: no email. |
| **Self-invocation.** Put the send and the transactional call in the **same class**, with the transactional one called via `this` | The account is saved **without a transaction** (check the SQL log: no rollback on failure) | `@Transactional` works through the proxy; a call inside the class skips it (Phase 0, now for real) |
| **Check-then-act consumption.** Consume by reading the token, checking `used_at`, then saving. Fire 20 concurrent verifies with the same token (command in the build order) | **More than one** 204 | Then switch to the conditional `UPDATE`: exactly one 204, nineteen 400s |
| **The prod profile with no sender.** Start with `prod` (supplying the `DB_*` variables for your local database) | Startup fails: no bean of type `EmailSender` | Failing loudly beats silently losing every email |
| **Mutation checks** once tests exist | e.g. drop the `used_at IS NULL` condition, use the DB's `now()`, store the raw token, remove `@Profile` | Each must turn a test red |

### Build order

1. **Settle decisions a–f.**
2. **Typed config** for the TTL and frontend URL.
3. **`EmailSender`**, `EmailMessage`, `LoggingEmailSender` (`dev` only), and the **capturing fake** in the shared test config. Run the existing suite: it must stay green.
4. **Migration V3** (check the hex `CHECK` rejects a raw-looking token, in a rolled-back transaction like V2).
5. **`TokenPurpose`, `UserToken`, the repository** with its two `@Modifying` queries.
6. **Token generation + hashing**, with a quick unit test (length, URL-safe characters, same input → same hash).
7. **The token service**: `issue` and `consume`. ⚠️ Deliberate failure: write `consume` as check-then-act first if you want to see the race (step 11).
8. **Registration issues the token**, and the **`RegistrationWorkflow`** sends after commit. ⚠️ Deliberate failures: the phantom email, then self-invocation.
9. **`POST /verify-email`**.
10. **`POST /verify-email/resend`.**
11. **Run it by hand:**
    - register → copy the link from the log → verify (204) → Basic login works
    - verify again with the same token → 400
    - resend for: an unverified account (new email in the log; the *old* link now fails), a verified account, an unknown email → all 202, identical bodies
    - `psql`: only a 64-character hex hash stored, never the raw token
    - the race: `seq 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -d '{"token":"<TOKEN>"}' localhost:8080/api/v1/auth/verify-email | sort | uniq -c`
    - optionally, the prod start without a sender
12. **Tests.**

### Tests

| Kind | Must prove |
|---|---|
| **Unit** | Token generation: 43 URL-safe characters, no padding, two tokens differ · hashing: deterministic, 64 lowercase hex, never equal to the raw token · `VerificationTokenService` with a fixed `Clock`: `issue` deletes old tokens and stores the hash (never the raw token); `consume` passes the clock's instant and maps 0 rows → `TOKEN_INVALID` · `RegistrationWorkflow`: sends **after** the service returns, doesn't send if the service throws, still returns if the sender throws · request `toString()` masks the token |
| **JPA slice** | The conditional `UPDATE`: 1 row the first time, **0 the second**; 0 when expired (the boundary instant), 0 for the wrong purpose · the `DELETE` removes only the user's unused tokens of that purpose · the hex `CHECK` rejects a raw token · `ON DELETE CASCADE` removes tokens with the user |
| **Integration** (with the capturing fake) | Register → the fake has one email containing a link → extract the token → verify 204 → Basic login 200 · the same token again → 400 · resend → a new email, and the old token → 400 · resend for unknown / verified / unverified → identical 202s, and **no email** for the first two · the stored hash is not the token |
| 📊 **Race** | 20 concurrent verifies with one token: exactly **one 204**, nineteen 400s, zero 500s |

🎯 **Interview questions:**
- "Design a password-reset token." (All six properties, and why each.)
- "Why SHA-256 for tokens but bcrypt for passwords?"
- "How do you make sure a token can only be used once, even with concurrent requests?"
- "Why is the token in the URL fragment, not the query string?"
- "What happens if the email server is down when someone registers?"
- "Why not send the email inside the transaction?"
- "How does your resend endpoint avoid revealing which emails are registered? What does it still leak?" (Timing.)

---

## 1.5 — Login, lockout, login history (~1h 45m)

> **Moved here from §1.4 (2026-09-25):** once `security_events` exists, email verification also records an **`EMAIL_VERIFIED`** event (§1.4 couldn't, because the table didn't exist yet). This section will be rewritten in the §3.1 layout when we start it.

**Login endpoint:**
- [ ] `POST /api/v1/auth/login` with `{email, password}` → 200 with a user summary (Phase 2 adds tokens to this response). It calls the `AuthenticationManager` **directly**, exposed as a bean from `AuthenticationConfiguration`.
- [ ] 💡 **The same exception, two routes.** An `AuthenticationException` thrown here comes out of your **controller**, so `@RestControllerAdvice` **does** catch it. Map it to a 401 `ProblemDetail`. The *same* exception thrown from `BasicAuthenticationFilter` never reaches the advice (§1.1). Once you've seen both, you'll understand exactly why Phase 2 needs an entry point.

**What the response reveals.** Your decision, with these facts:
- [ ] Unknown email and wrong password → the **same** 401 `AUTHENTICATION_FAILED`, the same body and similar timing. Spring does this for you. Don't undo it with a pre-lookup in your own code.
- [ ] 🔍 Read `AbstractUserDetailsAuthenticationProvider.authenticate`. The **locked** and **disabled** checks run *before* the password is checked. Taken as is, that means anyone can find out that an address is registered-but-unverified **without knowing the password**.
- [ ] 🏗️ Recommended: **locked** stays a pre-check (so a correct password during lockout reveals nothing) and returns the **generic** 401. **Not verified** moves to the **post**-checks (the provider lets you swap both check sets), so only someone who knows the password learns "verify your email first" (403 `EMAIL_NOT_VERIFIED`). Your call, justify it.

**Lockout:**
- [ ] N failed attempts → `locked_until = now + duration`. Both values come from typed, validated config (`taskflow.security.lockout.*`).
- [ ] Auto-unlock is **lazy**: nothing runs on a schedule. The account simply counts as unlocked once `locked_until` has passed (you built that into §1.2). A successful login resets the counter. 🏗️ Does the counter restart after a lock expires, or does one more failure re-lock straight away? Decide.
- [ ] **Lockout applies to every credential check**, so it covers Basic-authenticated requests too, not just `/auth/login`. ⚠️ **Trap:** putting the counter in the login controller leaves **Basic as an unthrottled side door** for brute force. Drive it from **authentication events** instead: `AuthenticationFailureBadCredentialsEvent` and `AuthenticationSuccessEvent`, which fire whichever path did the checking.
- [ ] ⚠️ **Trap: the silent listener.** Boot auto-configures a `DefaultAuthenticationEventPublisher`, and the managers Spring Security builds pick it up. **A `ProviderManager` you create with `new` has a no-op publisher**, and your listener never fires. Prove it fires, on both paths, with a test.
- [ ] ⚠️ **Trap, the main one of this phase. Create it first:** mark the login flow `@Transactional`, and write the failure counter in that same transaction. Authentication throws → the transaction **rolls back** → **the counter never increments** → lockout never happens, and nothing errors. The counter update needs its **own** transaction, which is Phase 7's `REQUIRES_NEW` showing up early. Or keep authentication out of any transaction. Either way, the proof is an integration test: N wrong passwords, then the **correct** password → still rejected.
- [ ] ⚠️ **Trap: lost updates.** Two concurrent failures both read `3` and both write `4`. Increment **in the database**, atomically: one `UPDATE … set failed_login_attempts = failed_login_attempts + 1`. No read-modify-write in Java. Know the side effect: a JPQL bulk update **bypasses the persistence context and JPA auditing**, so `updated_at` doesn't move. For a failed-login counter that's arguably right. (Preview of the deferred bulk-operations topic.)
- [ ] An attempt against an **unknown** email has no row to count. Fine: per-IP throttling is Phase 10's rate limiter.
- [ ] 🎯 **Lockout is a denial-of-service tool.** Anyone who knows your email can lock you out. Auto-unlock limits the damage, and per-IP limits (Phase 10) are the real fix. Have this answer ready. You'll be asked.

**Security events and login history (decision 6):**
- [ ] Migration `V4__create_security_events.sql`: `user_id` (FK, **indexed together with `occurred_at`** in the order the history query sorts), `event_type` (`CHECK`), `occurred_at`, `ip_address`, `user_agent` (truncated to a chosen length).
- [ ] 🏗️ This table is itself an audit record and is only ever appended to, so does it need `updated_at` / `updated_by`? Extend `BaseEntity`, or map id + `occurred_at` only. Decide.
- [ ] Record `LOGIN_SUCCEEDED` / `LOGIN_FAILED` / `ACCOUNT_LOCKED` **from `/auth/login` only**. ⚠️ **Trap:** recording from the authentication events writes a "login" for **every Basic-authenticated request**. That's why the counter follows the events but the history doesn't. Write down the asymmetry and the reason for it.
- [ ] ⚠️ **Trap: `X-Forwarded-For` is client-controlled.** Read `request.getRemoteAddr()`. Only trust forwarded headers when a known proxy is in front (`server.forward-headers-strategy`, not enabled now). Otherwise anyone can write any IP into your audit trail.
- [ ] IP addresses are **personal data** under GDPR. Write down a one-line retention stance. Don't build it.
- [ ] `GET /api/v1/users/me/login-history` → paginated with your Phase 0 machinery, sorted by `occurred_at desc`, then `id desc` (**stable sort**).
- [ ] 💡 **The first ownership rule in the codebase:** there is **no user id in the path**. The service takes the id **from the principal** (`@AuthenticationPrincipal`), never from the request. "Never trust a client-supplied ID" from §4 of the project context, made concrete, and the seed of Phase 4.

---

## 1.6 — Password reset & password change (~1h)

**Reset (the user is anonymous):**
- [ ] `POST /api/v1/auth/password-reset/request` with `{email}` → **always 202**. Unknown email, unverified email, locked account: every case gets the identical response.
- [ ] ⚠️ **Timing still leaks:** a known email does a DB write and sends an email, and an unknown one returns straight away. **Note it; don't fix it.** Phase 9's async sending removes most of the gap. Have the answer ready.
- [ ] Token rules as in §1.4, with purpose `PASSWORD_RESET` and a **much shorter** TTL, separately configured. Justify the number.
- [ ] `POST /api/v1/auth/password-reset/confirm` with `{token, newPassword}` → 204, all in **one transaction**: consume the token (conditional `UPDATE`), set the new hash, stamp `password_changed_at`, **invalidate every other outstanding reset token** for that user, and record `PASSWORD_RESET`.
- [ ] 🏗️ Does a successful reset **also clear the lockout**? (Recommended: yes. Proving you own the email is stronger evidence than the lockout guards against.) Does it **also verify** an unverified email? Decide both.
- [ ] 📊 **Race it**, as with the Phase 0 slug: fire 20 concurrent confirms with the same token. **Exactly one** 204, the rest 400, zero 500s.

**Change (the user is authenticated):**
- [ ] `PUT /api/v1/users/me/password` with `{currentPassword, newPassword}` → 204. **Re-authentication:** a valid Basic header isn't enough, so `currentPassword` must match. The new password has to differ from the current one. Stamp `password_changed_at` and record `PASSWORD_CHANGED`.
- [ ] 🏗️ **What status for a wrong `currentPassword`?** ⚠️ **Not 401.** A 401 tells the client "you are not authenticated", and a well-behaved client (Phase 2) throws away its tokens and logs the user out. The user *is* authenticated; they got one field wrong. Pick 400 or 403, and justify it. Does a wrong current password count toward lockout? Decide.

🎯 **Interview question:** "After a password change, what happens to the user's other logged-in devices?" Today: nothing, and you know that. Phase 2: every token issued before `password_changed_at` gets rejected. That's why the column exists now.

---

## 1.7 — Profile (~30 min, **first thing to trim**)

- [ ] `GET /api/v1/users/me` → id, email, username, displayName, timezone, role, emailVerified, createdAt. It **never** includes the password hash, the lock state or failed attempts. The response DTO decides what's public, which is Phase 0's over-exposure argument with a real hash behind it now.
- [ ] `PATCH /api/v1/users/me` with `{displayName?, timezone?}`, where `null` means "leave unchanged". 💡 A record **can't tell "field missing" from "field sent as null"**. Write that limit down: a real PATCH needs JSON Merge Patch or `JsonNullable`. Not now.
- [ ] `timezone` must be a valid IANA **region** ID. ⚠️ **Trap:** `ZoneId.of` also accepts `+05:30`, `UTC+1` and `Z`. Fixed offsets are *not* timezones, because they ignore daylight saving. Validate against `ZoneId.getAvailableZoneIds()`.
- [ ] Email change is **out of scope**: it needs re-verification of the new address, and it's a whole feature.

---

## 1.8 — Tests (you write these; estimates above include them)

Patterns come from `TESTING_GUIDE.md`. What's **new** this phase:

**New tools:**
- **`spring-security-test`**: `@WithMockUser`, `@WithUserDetails`, and the MockMvc post-processors `with(httpBasic(…))` / `with(user(…))` / `with(anonymous())`.
- **An adjustable `Clock`**, registered in test configuration, so a test can "move forward 31 minutes" without sleeping.
- **A capturing `EmailSender`** test bean that records sent messages. Integration tests take the raw token out of it, which is exactly how a real user gets it.

⚠️ **Existing tests will break the moment the starter lands.** Expect it; don't `@Disable` anything.
- `OrganizationControllerTest`: a `@WebMvcTest` slice may **not** load your `SecurityFilterChain`, because it's a `@Bean` inside a `@Configuration`, not a scanned filter. It then gets Boot's **default** chain instead, which gives **401** on GET and **403 on POST (CSRF!)**. Check which one you're getting. The fix is to `@Import` your security config and supply whatever beans it needs (`@MockitoBean` for the `UserDetailsService`).
- `OrganizationApiIntegrationTest`: now 401. Create a verified user in setup and use `restTemplate.withBasicAuth(…)`.
- 📌 **Observed 2026-09-24 (starter only, no config yet): 6 of 13 tests fail.**
  - The slice gives GET 401, POST 403.
  - The integration test gives GET 401, POST **401 or 302**, not 403. It's the same CSRF rejection, but real Tomcat runs an `ERROR` dispatch to `/error`, the default chain secures `/error`, and the anonymous caller gets the entry point instead. The 302 is the default chain's **form-login** entry point redirecting to `/login`.
  - MockMvc never runs the `ERROR` dispatch, so the slice shows the raw 403. **Same cause, different status, depending on the kind of test.**

**Required tests. At least one per rule, and each must fail when its bug is planted** (the testing guide's mutation check):

| Kind | Must prove |
|---|---|
| Unit | Email normalisation · token hashing (the raw value never equals the stored value) · lockout threshold and expiry **using the adjustable clock** · the request record's `toString` never contains the password · the 72-byte boundary (72 passes, 73 → rejected) |
| Web slice | Anonymous `GET /users/me` → 401 · the register validation 400 **does not echo the password** anywhere in the body · the `/auth/*` endpoints are reachable anonymously, and **only** with POST |
| JPA slice | Uniqueness of the lowercased email · `CHECK` rejects an uppercase email (flush it! guide §6.3) · the atomic counter increment · the conditional token-consume UPDATE returns 0 on its second run · auditing writes the **username** as `created_by` (and `"system"` for an anonymous registration) |
| Integration | Register → capture the token → verify → login 200 · login **before** verification → the response you chose · **N bad passwords, then the correct one → still 401** (the rollback trap) · the same through Basic (the side door) · move the clock past `locked_until` → login works · reset token used twice → second is 400 · reset for an unknown email → 202 and **no** email captured · `GET /organizations` anonymously → 401, with a user → 200 |
| Security | One test per row of the §1.1 access table. `/actuator/metrics` with a `USER` → 403, with an `ADMIN` → 200. |

📊 **Measure:** suite time and container count before and after this phase. bcrypt plus a second full context can quietly double both. If a new context appears, work out **which** configuration difference caused it (guide §7).

---

## Definition of done

Check these literally, by running them.

- [ ] No generated password in the startup log. The `--debug` report has been read, and the backing-off condition written down.
- [ ] Register → the verification link is in the dev log → verify → login 200. The `user_accounts` row shows a `{bcrypt}` hash, a lowercase email, and `created_by` = `system`.
- [ ] Login before verification behaves exactly as decided in §1.5.
- [ ] N wrong passwords → locked; the correct password is still rejected; after the lock duration it works. **Also tested through Basic.**
- [ ] Unknown email and wrong password give byte-identical response bodies (except `timestamp` and `correlationId`).
- [ ] Reset for an unknown email and for a known email → both 202 with identical bodies.
- [ ] A reset token works once. The second use and the concurrent race both give exactly one success.
- [ ] No password, token or email appears anywhere in the logs at INFO (the `dev` email sender is the one documented exception).
- [ ] Every row of the access table behaves as specified. The last rule is `denyAll()`.
- [ ] No `JSESSIONID` cookie in any response. 401s carry `X-Correlation-Id`.
- [ ] After a password change, `password_changed_at` has moved, and a `PASSWORD_CHANGED` event exists.
- [ ] Suite green; the Phase 0 tests are fixed, not disabled.
- [ ] README updated: the auth endpoints, how to register and verify in dev (where the link appears), and the Basic auth note.
- [ ] Small commits, roughly one per section.

---

## Deliberate failures: create each bug, see it, then fix it

These go into section 7 of your learning log. They're interview stories, not theory.

| Break | Expect |
|---|---|
| Starter added, no beans | Every endpoint 401; a generated password in the log |
| Grant `"ADMIN"` as a plain authority, check with `hasRole("ADMIN")` | **403** for a real admin. `hasRole` adds the `ROLE_` prefix itself |
| Failure counter written inside the login transaction | Counter stays 0; lockout never happens; no error anywhere |
| `new ProviderManager(…)` without a publisher | Listener never fires; lockout never happens |
| Auditor checks only `isAuthenticated()` | `created_by = anonymousUser` |
| `log.debug("{}", registerRequest)` | Plaintext password in the log |
| Token consumed with read-then-write | The 20-way race gives more than one 204 |
| 73-byte password with no byte check | A 500 (or silent truncation, depending on version: find out which) |

---

## Explicitly NOT in Phase 1

JWT, refresh tokens, logout, revocation · JSON 401/403 from the filter chain (`AuthenticationEntryPoint`, `AccessDeniedHandler`) · CORS · method security (`@PreAuthorize`, Phase 4) · org or project roles · OAuth2 / social login / MFA · remember-me, server sessions · email change, account deletion · real SMTP, async sending, token cleanup job (Phase 9) · per-IP rate limiting, CAPTCHA (Phase 10) · breached-password checking (know the HIBP k-anonymity idea for interviews, don't build it) · OpenAPI.

---

## 🎯 Interview questions: answer these *during* Phase 1

1. Walk me through what happens when a request with a Basic header reaches your app, filter by filter.
2. `AuthenticationManager` vs `AuthenticationProvider` vs `UserDetailsService`: what does each one do?
3. How is a password stored in your system? Why bcrypt? What's the `{bcrypt}` prefix for?
4. Why SHA-256 for reset tokens but bcrypt for passwords?
5. Design a password-reset flow. What makes the token secure?
6. How does your login avoid user enumeration, including timing? Where else could enumeration leak?
7. How does your lockout work under concurrent requests? Why isn't the counter lost when authentication fails?
8. Isn't account lockout a denial-of-service vector? What would you add?
9. `hasRole` vs `hasAuthority`: what's the difference?
10. Where is the `SecurityContext` stored, and why must it be cleared?
11. `anyRequest().authenticated()` vs `denyAll()`: which is deny-by-default?
12. Why is disabling CSRF safe for your API, and when would it *not* be?
13. Why can't `@RestControllerAdvice` handle every `AuthenticationException`?
14. How do you test time-dependent logic like token expiry and lockout?

---

## Session split (~7h)

| Session | Sections | Est. | Actual |
|---|---|---|---|
| 1 | 1.1 + 1.2: starter, filter chain, users schema, encoder, `UserDetailsService`, auditor. Fix the Phase 0 tests. | ~2h | |
| 2 | 1.3 + 1.4: registration, email sender, verification tokens | ~2h | |
| 3 | 1.5: login, lockout (the rollback and event traps), security events | ~1h 45m | |
| 4 | 1.6 + 1.7: reset, change, profile, README | ~1h 30m | |

Over budget? **Trim in this order:** §1.7 profile → the login-history *endpoint* (keep **recording** the events) → the password-change endpoint. **Never trim:** hashed single-use tokens, the lockout rollback test, the access-rule tests.

**Before session 1:** re-read the Spring Security reference pages *Servlet Architecture* and *Authentication Architecture* (the plan's week-0 reading). The Concepts section above assumes them.

---

## Phase 1 notes (15 min at the end; these become interview stories)

**What I built:**

**What confused me:**

**What I learned:**

**Traps I actually hit:**
