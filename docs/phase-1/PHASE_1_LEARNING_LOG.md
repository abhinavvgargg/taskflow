# Phase 1 — Learning Log (Users & auth core)

> **Phase closed 2026-10-01.** Consolidated the same day: organised by **theme**, not by sub-section. The sub-section detail (briefs, requirements, per-step deliberate failures, test plans) lives in `PHASE_1_REQUIREMENTS.md`.
> Companions: `SECURITY_TESTING_GUIDE.md` (how the access rules are tested) · `../phase-0/PHASE_0_LEARNING_LOG.md` (the foundations).

---

## 1. What was built

| Capability | How it works | Key pieces |
|---|---|---|
| **The gate** | One `SecurityFilterChain`: stateless HTTP Basic (a bridge until Phase 2's JWT), CSRF and logout off, six POST-only auth paths public, `/api/v1/**` authenticated, **`denyAll()` last**. Health public, its details `ADMIN`-only. | `TaskflowSecurityConfig` |
| **Accounts** | `user_accounts` with constraints as the final guard; bcrypt through `DelegatingPasswordEncoder`; a separate principal (`TaskflowPrincipal`); the auditor writes the app username | V2, `UserAccount` (`@DynamicUpdate`), `TaskflowUserDetailsService`, `AuditAwareImpl`, `Normalize` |
| **Registration** | `POST /auth/register` → 201. Duplicates → 409 with the field named, also under a race. | `UserRegistrationService`, `@ValidPassword`, `@MaxUtf8Bytes` |
| **Tokens and email** | 32 random bytes, SHA-256 at rest, expiring, single-use (conditional `UPDATE`), purpose-bound, one live link per user and purpose (partial unique index). Links carry the token in the URL fragment. Emails go out after commit; dev logs them, prod has no sender yet. | V3, `UserTokenService`, `TokenCodec`, `AccountEmails`, `RegistrationWorkflow` |
| **Verification** | `POST /auth/verify-email` (204) · `/verify-email/resend` (always 202) | |
| **Login** | `POST /auth/login`: 200; **401** for unknown, wrong or locked (identical bodies); **403** only for unverified **with the correct password** | `LoginService`, one `DaoAuthenticationProvider` bean with custom checks |
| **Lockout** | 5 wrong passwords → 15 minutes, on **every** password path (Basic included). Driven by authentication events; counter in SQL, in its own transaction. | `AuthenticationEventsListener`, `LoginAttemptService` |
| **Security log** | Append-only `security_events` (`inet` IP, `CHECK`s, `UPDATE` rejected by a trigger): login success/failure, lock, verification, reset, change. `GET /users/me/login-history`. | V4, `SecurityEvent`, `SecurityEventRecorder`, `LoginHistoryService` |
| **Password reset** | `request` (always 202, link to the stored address, 30 min) → `confirm` (204): unlocks, verifies, records, notifies the owner | `PasswordWorkflow` + `PasswordService` |
| **Password change** | `PUT /users/me/password`: re-authenticates through the manager (counts toward lockout), revokes the outstanding reset link, records, notifies | same |

**Not built:** §1.7 profile (moved to Phase 2), email change (backlog, `PROJECT_CONTEXT.md` §6), OpenAPI (after Phase 2). **Tests for §1.3–§1.6 are owed** (§8).

**Commits:** `9211684` · `56340ae` · `1fc0f54` · `02bc36b` · `ece675b`…`5085ed7` (§1.4) · `aa7efca`, `a2cd682` (§1.5) · `63bb1b4`, `4e115a2` (§1.6) · plus doc commits.

### Evidence

| | |
|---|---|
| **Suite** (run by me on a scratch copy, 2026-10-01) | **76/76 green**, ~13s including Maven start, **2** Postgres containers (one per context type). It covers §1.1–§1.2 and Phase 0; nothing after §1.2 has an automated test. Growth: 13 → 45 (§1.1) → 76 (§1.2, 9.4s); new test classes reused the cached contexts (e.g. the security integration test: 13 tests in 0.44s). |
| **Verified by me** | Every Spring Security / Boot / Hibernate behaviour marked *(verified)* below, from the sources in `~/.m2` or an experiment on a scratch copy · V4 in a rolled-back transaction (every constraint, the trigger, the index plan, the cascade) · the §1.4 resend race in the database (10 requests → 4 tokens issued, 6 collisions answered 202 without sending) |
| **Reported by you** (manual runs, detailed numbers not always sent) | Registration, verification, resend, both races of §1.4 · lockout on both paths, deliberate failures 1 and 3 of §1.5 · the full §1.6 run list, including the 20-way reset race |

---

## 2. Decisions

Dated entries with alternatives are in `PHASE_1_REQUIREMENTS.md`'s Decisions table (1–28). Here, by theme, with the reason in one line:

**Identity model**
- **Email logs in; a separate lowercase username (no `@`) goes into `created_by`**, so audit columns survive an email change and the two identifiers can't collide.
- **`user_accounts` / `UserAccount`**: `user` is reserved in Postgres, `User` is Spring's class.
- **Single `role` column (`USER` / `ADMIN`)**: platform roles are one fact per user; many-roles belongs to Phase 3–4 memberships.
- **A separate principal class in `common/security`** (not the entity, not a record): the security context lives outside transactions, and the auditor in `common` must read it. **No `CredentialsContainer`**: the principal's `toString()` doesn't print the hash and the context is never stored.
- **Email and username normalised in one place (`Locale.ROOT`)**, any case accepted.

**The gate**
- **HTTP Basic, stateless**, deleted in Phase 2 · chain bean `taskflowSecurityFilterChain` (not Boot's default name) · health public, details `ADMIN`-only.

**Passwords**
- **At least 12 characters, at most 72 UTF-8 bytes, no composition rules**, in one composed `@ValidPassword`. The login and current-password fields get only presence and the 72-byte cap, never the minimum.

**Registration**
- **409 `EMAIL_ALREADY_REGISTERED`, knowingly revealing registered emails** (login and reset never do) · **201 with no `Location`** (there's no URL the caller can read) · **`saveAndFlush` + translation by constraint name inside `user`**.

**Tokens and email**
- **One `user_tokens` table**, purpose-constrained · **revoke on reissue + partial unique index** (one live link per user and purpose) · **the token in the URL fragment** · lifetimes in typed config: **24h verification, 30 min reset** (lifetime follows damage) · **`Propagation.MANDATORY`** on issue/consume · **send after commit** from non-transactional workflow beans · a failed send is logged by account id and never fails the request · `UserToken` → `UserAccount` is `LAZY`.

**Login and lockout**
- **Locked = a pre-check with the generic 401; unverified = a post-check with 403**.
- **5 → 15 minutes; the counter resets when the lock is applied**; only wrong passwords count.
- **One `DaoAuthenticationProvider` bean; the manager from `AuthenticationConfiguration`**, so Basic and `/login` share checks and events.
- **Counter from events, in `REQUIRES_NEW`, failing closed**; `LOGIN_*` recorded by the endpoint only, `ACCOUNT_LOCKED` on every path.

**Security log**
- **`security_events`: `inet`, all six types in the `CHECK`, `failure_reason` only on `LOGIN_FAILED`, no rows for unknown emails, `ON DELETE CASCADE`, 12-month retention (stance)**.
- **Append-only**: `IdentifiedEntity` (id only), `@Immutable`, an `UPDATE`-rejecting trigger; `Long userAccountId`, not a relation.
- **A record commits with what it records**: attempts on their own, state changes with the change.

**Reset and change**
- Reset **clears the lock** and **verifies the email** · the owner is **notified** after reset or change · a wrong current password is **400** and **counts** · **`@DynamicUpdate` on `UserAccount`**.

**Process**
- **Tests for §1.3–§1.6 deferred** (decided per sub-section; confirmed 2026-10-01: **none before Phase 2**) · **§1.7 moved to Phase 2** as a side task · **email change to the backlog** · `LoginFailedException` in `common/error` (your call, better than the brief's).

---

## 3. Lessons, by theme

Each point is the rule, then what taught it. ***(Hit)*** means it actually happened in this project.

### Spring Security configuration

- **Matchers are resolved per request, not at startup.** A green start proves nothing. *(Hit twice)* `"/auth/{register, login}"` is a path-variable capture, not a list: **every POST** returned Tomcat's HTML 500. `EndpointRequest.to("health/**")` takes endpoint **IDs**: **every GET** returned 500. The access table as a parameterised test catches both in under a second.
- **`denyAll()` last, not `authenticated()`**: otherwise every forgotten endpoint is open to every self-registered user. **First match wins**, so specific rules go before `/api/v1/**`.
- **`permitAll()`, not `anonymous()`**: `anonymous()` refuses logged-in callers. *(Hit: 403 on health.)*
- **Restrict health details, not the health endpoint**: probes are anonymous, so a locked endpoint means an orchestrator restart loop *(hit)*. `when_authorized` without `roles` means **any** authenticated user.
- **`/error` must be permitted**: `sendError` triggers an internal `ERROR` dispatch through the chain again.
- **CSRF off only with the reason written down**: browsers resend cached Basic credentials, so Basic is safe only as an API-client bridge; bearer headers (Phase 2) aren't attached automatically.
- **`LogoutFilter` is on by default** and handles `/logout` before authorization: disabled until Phase 2.
- **Your beans make Boot back off** (verified): any `SecurityFilterChain` removes the default chain; any `UserDetailsService`, `AuthenticationProvider` or `AuthenticationManager` (or a `JwtDecoder`, or OAuth2 resource-server classes: Phase 2) removes the generated user, and with it `spring.security.user` in test config *(hit: tests went 401, now `TestUsers` creates real ones)*. `@EnableWebSecurity` was redundant.
- **The chain:** `DelegatingFilterProxy` → `FilterChainProxy` (firewall, clears the context, picks the first matching chain) → context holder, headers, Basic, anonymous, exception translation, authorization. `CorrelationIdFilter` runs outside it, so rejected requests keep their ID *(verified)*.
- **401 vs 403:** a wrong password never reaches authorization (Basic calls the entry point itself); `denyAll()` gives an anonymous caller 401, not 403. Filter-chain 401s bypass `@RestControllerAdvice`; the same `AuthenticationException` thrown from a controller doesn't. Phase 2 needs an `AuthenticationEntryPoint`.

### The authentication pipeline

- **Manager picks a provider; the provider orchestrates; the user service only finds; the encoder only compares.**
- **Check order** *(verified, 6.5.11)*: load → **pre-checks** → password → **post-checks**. A failing pre-check still pays the bcrypt (`alwaysPerformAdditionalChecksOnUser`), but its exception wins. Spring's defaults therefore reveal "unverified" and "locked" from an email alone. Unverified bob with a **wrong** password got **403** under the defaults *(hit, deliberate failure 1)*; with our checks it's `BadCredentialsException`.
- **Where the manager comes from** *(verified)*: Boot's event publisher → `AuthenticationConfiguration` builds the global manager from the **one** provider bean → `HttpSecurity`'s manager delegates to it. The documented `new ProviderManager(...)` has a no-op publisher and, while a `UserDetailsService` bean exists, isn't what Basic uses. The `WARN` from `InitializeUserDetailsBeanManagerConfigurer` is expected.
- **Events:** one per `authenticate()`, synchronous, on the request thread. Bad credentials and unknown users share `AuthenticationFailureBadCredentialsEvent`; locked and disabled have their own. The event carries the name **as typed**, so normalise.
- **`UserDetails`'s flag methods default to `true`** since 6.3: fields without overrides let locked and unverified users in *(hit)*. `getUsername()` is the **email**; `authentication.getName()` in the auditor wrote emails into `created_by` *(hit)*. `isAuthenticated()` is true for the anonymous token. Authorities need `ROLE_` for `hasRole`.
- **Lock boundary:** unlocked **from** `locked_until` onward; the inverted check locked users forever *(hit)*.
- **Re-authentication** for sensitive changes goes through the manager, so it's counted; checking the stored hash as the typed password rejected every change and **locked the user out after five tries** *(hit, caught in review)*.

### Passwords

- **bcrypt** is slow and salted, with the salt inside the hash; the `{bcrypt}` prefix lets `DelegatingPasswordEncoder` migrate algorithms without a big-bang reset.
- **72 bytes, not characters:** `@Size` counts UTF-16 units (an emoji is 2, and 4 bytes). *(Verified)* On 6.5.11, `encode()` throws past 72 bytes (a 500 without `@MaxUtf8Bytes`, *hit*); on 6.3.1 a **different password logged in** (truncation). `matches()` still compares only the first 72 bytes, so login needs the cap too.
- **Custom constraints:** an annotation plus a `ConstraintValidator`; `null` is valid (presence is `@NotBlank`'s job); messages live in **`ValidationMessages.properties`**, plural *(hit: the singular name showed the raw `{…}` key)*. **Composed** ones *(verified)*: each part reports its own message, and each part must allow `ANNOTATION_TYPE`.
- **Normalise once, then check and insert with the same values:** checking the raw input let the constraint do the work, and with both fields taken the wrong 409 could win *(hit, review)*.
- **Records print every component:** one `log.info("{}", request)` put a plaintext password in the log *(hit)*. Mask every record holding a secret or an email.

### Tokens

- **Six properties:** unguessable (`SecureRandom`, 256 bits), hashed at rest, expiring, single-use, purpose-bound, invalidated on reissue. Plus: never in URLs sent to servers, never logged outside dev.
- **SHA-256 for tokens, bcrypt for passwords:** a 256-bit token can't be guessed at any speed, and a deterministic hash is what lets the database find the row.
- **Single use under concurrency:** one conditional `UPDATE`, branch on the row count; check-then-act lets two requests win.
- **Only the fragment** stays out of logs, proxies and `Referer`, and scanners can't consume a POST-only token.
- **Lifetime follows damage:** 24h verification, 30 min reset.

### Transactions and persistence

- **Flush ≠ commit.** `saveAndFlush` makes a constraint violation surface **inside** your `try`; `save()` defers it past the method. The row is still invisible and undoable until the outermost `@Transactional` returns.
- **After a failed flush the transaction is rollback-only.** Catch to **translate** inside; catch to **continue** only outside. Swallowing inside gives `UnexpectedRollbackException` *(hit in resend)*.
- **Irreversible side effects after commit, from a separate bean.** Sending inside the transaction means phantom emails *(the §1.4 workflow was wired backwards, caught in review)*. Calling through `this` skips the proxy *(hit: a `MANDATORY` wrapper that did nothing)*.
- **`REQUIRES_NEW`** survives the caller's rollback. The rollback trap: counter in the login's transaction → **stayed 0, no error** *(hit, deliberate failure 3)*. It costs a second connection, so the login flow has no transaction.
- **A record commits with what it records:** attempts alone, state changes with the change.
- **Hibernate writes the whole row** *(verified)*: a bulk unlock followed by an entity save put the lock back; `clearAutomatically` lost the password change instead; `@DynamicUpdate` kept both. Any load-then-save can also erase a concurrent lock.
- **`@Modifying` bypasses the persistence context and auditing** (`updated_at` doesn't move).
- **Derived queries don't select columns:** `findIdByEmail` loaded the entity and threw `JpaSystemException` for every existing account *(hit, verified)*. Write JPQL.
- **DB defaults don't apply to Hibernate inserts** (every column is sent) · **records bind through the constructor, and a compact constructor runs before the fields are set** (an NPE on every start, *hit*) · `@Profile` needs `@Component` · `@Table` has `name`, not `value` *(hit)* · **applied migrations are frozen** (V1–V4).

### Enumeration, timing and lockout

- **Registration reveals registered emails, by design; login, resend and reset never do** (same status and body).
- **Timing still leaks** at resend, reset request and login: a known email does writes or sends mail. Phase 9's async sending removes most of it; login's gap is **unmeasured**.
- **Lockout:** the counter increments in SQL; a conditional `UPDATE` applies the lock and its row count names the one request that did; it fails closed. A listener that caught `DataAccessException` plus the broken `findIdByEmail` would have left lockout **silently off** behind normal 401s *(caught in review)*.
- **Lockout is a denial-of-service tool.** Auto-unlock limits it; per-IP rate limiting (Phase 10) is the real fix.

### Schema as the final guard

- Constraints catch what code forgets: lowercase email, username format without `@`, `{id}`-prefixed hash, role values, token hash as 64 hex, token purpose, event type/reason pairs. **Name every constraint**; Postgres truncates identifiers over 63 characters.
- `password_hash varchar(200)`: sized for the longest encoder you might migrate to.
- **`inet`** validates and canonicalises (`0:0:0:0:0:0:0:1` = `::1`) *(verified)*, and Hibernate 6.6 maps `InetAddress` to it with no annotation.
- **`@Immutable` ignores changes silently**; the trigger makes a mistake loud.
- **Every FK gets an index and an `ON DELETE` rule.**
- **`ddl-auto: validate` doesn't check primary keys**: `user_tokens` shipped without one until review *(hit)*.
- **`common` never imports a feature** *(hit twice: `TokenProperties` importing `TokenPurpose`; the security config importing `TaskflowUserDetailsService`)*.

### Logs, PII and client identity

- Log **ids**, never emails, tokens or passwords. The dev email sender is the one documented exception. The dev profile's SQL bind logging (`TRACE`) prints emails and hashes: dev only, below `INFO`.
- **The client IP is the socket address.** `X-Forwarded-For` is client-controlled. Boot turns forwarded-header handling on by itself on a detected cloud platform *(verified)*: decide it in Phase 11. `InetAddress.getByName(null)` returns the loopback address.
- **API names are contracts:** `occuredAt` and `password` vs `newPassword` were caught before any client existed.

### Testing Spring Security

- **A slice with the config and no controllers turns "let through" into 404.** Slices silently can't test actuator rules (`EndpointRequest` matches nothing) or real credentials (they run as `dev`) *(verified)*. They load no services, so a config needing one needs a mock of the **interface**.
- `user(…)` tests authorization only; `httpBasic(…)` tests the password check.
- **Run feature tests as the weakest role that should pass** *(hit)*. Build clock-dependent services by hand with `Clock.fixed`; clear `SecurityContextHolder` in `@AfterEach`; prove an enum is stored as text with a native query; hash the shared test password once (bcrypt is slow on purpose). **Test config belongs in `src/test/resources`**: in `src/main` it shipped an admin login in the jar *(hit)*.
- **Same cause, different status:** the starter alone gave the slice 403 (CSRF) and the integration test 401/302 (`ERROR` dispatch, form-login entry point).
- **Mutation-check every test:** 15/15 planted bugs turned the suite red (§7).

### Also caught in review (one line each)

- `/login` without exception translation → every wrong password a 500 with a stack trace.
- A response built from the principal → null `displayName` / `createdAt`, `emailVerified` from `isEnabled()`, an empty 200 for an unexpected principal.
- `UserAccountResponse` hard-coding `emailVerified: false` → wrong for every verified user once reused.
- Unknown exceptions wrapped in `RuntimeException`; `.get()` on lookups; `applyChange` silently doing nothing (a 204 and a "password changed" email for nothing).
- Event factories accepting a failure reason → a `CHECK` violation (500) at the moment an account locks *(verified)*.
- History with one type and `OrderBy` in the method name; `PageableFactory` defaulting to `createdAt` (a 500 on an entity without it); an overload skipping the stable-sort tiebreaker.
- `Instant.now()` inside an entity; a `…Response`-named carrier holding a raw token; a result type with a `null` token; `.with("field", …)` called twice on a map.
- 200 instead of 201 on a create; `@Size(min = 3)` on display names ("Li", 王); a 409 without the `field`; the email in a failure log; the verification link hard-coded to `localhost:3000`.
- Resend not normalising → a silent 202 with no email; two sources for token lifetimes; a `CHECK` comparing two clocks (`expires_at > created_at`).
- Principal flags hard-coded `true` and authorities `null`; no factory to create a `UserAccount`; an IDE `java.awt.*` import.
- *My own notes* said to record `ACCOUNT_LOCKED` from `/login` only, which would miss locks caused through Basic.

### Tooling

- IntelliJ's "Delegate to Maven" runs the tests before the app; use `./mvnw spring-boot:run`.
- `--debug` goes through `-Dspring-boot.run.arguments=--debug`.
- `spring-boot:run` doesn't reload code: restart after a change *(hit)*.
- `psql -c "…"` expands `$` inside bcrypt hashes; the table has no id default, so use `nextval('global_id_seq')`.
- After a deliberate failure, check the diff for leftovers *(hit)*.
- Stage new files too: a pushed commit didn't compile its tests *(hit)*.

---

## 4. Production practices, and what each prevents

| Practice | Prevents |
|---|---|
| Deny by default, method-restricted exact permits, specific → general | Endpoints silently open to every logged-in user; permits opening other methods |
| Stateless, CSRF off with the reason, logout off until built, default security headers | Session state and fixation; forgetting CSRF when cookies arrive; unguarded `/logout` |
| Health public, details `ADMIN`-only, rules by `EndpointRequest` | Probe restart loops; infrastructure reconnaissance; rules drifting from paths |
| `DelegatingPasswordEncoder`, `{id}`-prefix `CHECK`, 12-char / 72-byte rule in one annotation | Big-bang rehashes; unverifiable hashes; weak or drifting password rules; truncation |
| Normalise once (`Locale.ROOT`) + lowercase `CHECK`s | Duplicate accounts by case; the Turkish `ı` bug |
| Separate immutable principal with id and app username; auditor checks the type | Detached entities in the context; a query per request; PII or `anonymousUser` in audit columns |
| Injected `Clock`, instants passed into entities and queries | Untestable time rules; two time sources |
| Validation, duplicate checks and token checks before bcrypt | CPU exhaustion through a ~100 ms hash per garbage request |
| Masked `toString()` on records with secrets or emails; logs carry ids only | Passwords, tokens and PII in logs |
| `saveAndFlush` + translation by constraint name, in the feature | A race returning a different, generic code (and the constraint name) |
| Tokens: `SecureRandom`, SHA-256 + hex `CHECK`, conditional `UPDATE`, one error code, fragment links, partial unique index | Guessable or leaked links; double use; oracles; scanners; several live links |
| Side effects after commit from a separate bean; catch-to-continue outside the transaction | Phantom emails; mail holding transactions; `UnexpectedRollbackException` |
| Locked as a pre-check, unverified as a post-check; translate only the expected exceptions | Enumeration without a password; a password oracle during lockout; outages reported as "wrong password" |
| Counter from events, in SQL, `REQUIRES_NEW`, failing closed; conditional reset | A side door around lockout; lost updates; the rollback trap; silent lockout failure; a write per request |
| Append-only events (`@Immutable` + trigger), `CHECK`s, `inet`, IP from the socket | Rewritten history; impossible rows; forged IPs |
| Records commit with what they record | Events for rolled-back changes; attempts lost with their failure |
| `@DynamicUpdate` + entity methods for account changes | A save undoing a bulk change or erasing a concurrent lock |
| Reset: always 202, stored address, 30 min, validate → consume → hash, unlock + verify + events in one transaction, notify the owner; change revokes the reset link | Enumeration; long-lived takeover links; burnt links; half-written resets; unnoticed takeovers; links that undo a change |
| Owner from the principal, never the path; stable, index-backed history sort | IDOR on other users' data; skipped or repeated rows |
| Tests: weakest role, boundary pairs, the access table as data, test config in `src/test`, mutation checks | Admin-only bugs hidden; rules that pass for everyone; shipped test credentials; tests that can't fail |

---

## 5. Interview questions (answerable now)

**Filter chain and configuration**
1. **What runs between a request arriving and your controller?** `DelegatingFilterProxy` → `FilterChainProxy` → the chain's filters (context, headers, Basic, anonymous, exception translation, authorization) → `DispatcherServlet`.
2. **Why do `DelegatingFilterProxy`, `FilterChainProxy` and `SecurityFilterChain` all exist?** Container-to-Spring bridge · one entry that picks a chain and clears the context · your chains, one per URL space.
3. **401 vs 403, and why does `denyAll()` give an anonymous caller 401?** Unidentified vs refused; the anonymous caller is sent to the entry point.
4. **Why isn't `anyRequest().authenticated()` deny-by-default?** Undeclared endpoints are open to every logged-in user.
5. **`permitAll()` vs `anonymous()`?** Everyone vs only unauthenticated callers (I hit it on health).
6. **When is disabling CSRF safe?** When the browser doesn't attach credentials automatically: bearer headers yes, cookies no, Basic only for API clients.
7. **Why can't `@RestControllerAdvice` handle a Basic 401?** It happens in the filter chain, before the dispatcher; the same exception from a controller is handled.
8. **The config started fine but every request was a 500. How?** Matchers are resolved per request; a bad pattern and a bad endpoint ID only failed then.
9. **How did you secure health, and what happens if liveness needs auth?** Endpoint public, details `ADMIN`-only; otherwise the orchestrator restarts a healthy app in a loop.
10. **How does Boot's default security back off?** Independent `@ConditionalOnMissingBean`s for the chain and for the generated user.
11. **Why isn't `STATELESS` enough to guarantee no sessions?** It governs Spring Security only; verify there's no `Set-Cookie`.

**Authentication and the principal**
12. **Manager vs provider vs user service vs encoder?** Picks · orchestrates · finds · compares.
13. **Why doesn't your entity implement `UserDetails`, and what's in the context?** It would be a detached stale row outside transactions; the context (a ThreadLocal) holds a small immutable principal.
14. **`hasRole` vs `hasAuthority`?** `hasRole` adds `ROLE_`.
15. **Your `created_by` filled with emails. Why?** `getName()` is the principal's `getUsername()`, the login email.
16. **How do you test time-dependent rules?** Inject a `Clock`; test the exact boundary instant.
17. **Why `Locale.ROOT` when lowercasing?** Under Turkish, `I` becomes dotless `ı`: a different email.
18. **Your column has `DEFAULT 'USER'`, yet the insert failed on NOT NULL. Why?** Hibernate sends every mapped column, unset ones as `NULL`.
19. **How is the first admin created?** An `UPDATE` by hand; never a seed user in a versioned migration.
20. **Where does your `AuthenticationManager` come from, and how do Basic and `/login` share it?** One provider bean → the global manager with Boot's publisher → `HttpSecurity` delegates to it; `/login` injects it.

**Passwords**
21. **How is a password stored, and why does the same one hash differently?** bcrypt with a per-hash salt inside the hash.
22. **What's `{bcrypt}` for; how would you move everyone to argon2?** The prefix picks the verifier; change the default and rehash on next login (`upgradeEncoding` + `UserDetailsPasswordService`).
23. **Why not `@Size(max = 72)`?** UTF-16 units aren't bytes; the encoder throws (6.5) or truncates (6.3.1: a different password logged in).
24. **How could a password end up in your logs?** A record's generated `toString()`.

**Registration and tokens**
25. **Does registration leak which emails exist?** Yes, knowingly. Login, resend and reset don't by status or body, but resend and the reset request still leak by timing (a known email writes and sends); Phase 9's async sending removes most of it.
26. **Two registrations with one email at the same instant?** The constraint stops the second; `saveAndFlush` makes it the same 409. *(Reasoned; the race wasn't run.)*
27. **Why 201 without `Location`?** No URL the caller can read.
28. **Why normalise before the duplicate check?** Otherwise the constraint does the work and the wrong code can win.
29. **Design a verification or reset token.** The six properties, POST-only, fragment links.
30. **SHA-256 for tokens, bcrypt for passwords: why?** Entropy and lookup.
31. **Single use under concurrency?** One conditional `UPDATE`, row count.
32. **Mail server down at registration?** Committed, logged by id, still 201, resend recovers.

**Transactions and persistence**
33. **Why not send inside the transaction?** Phantom emails; transactions held open for mail.
34. **Flush vs commit?** Flush sends SQL inside the transaction; commit makes it permanent and visible.
35. **You caught the exception; why did the transaction still fail?** A joined method marked it rollback-only.
36. **`MANDATORY` and `REQUIRES_NEW`: when, and at what cost?** Must join / must survive the caller; the latter takes a second connection.
37. **Your reset unlocked the account and the lock came back. How?** The whole-row entity `UPDATE`; `@DynamicUpdate` fixes it, `clearAutomatically` loses the other change.
38. **Why did your record's compact constructor throw an NPE?** It called an accessor before the fields were set.
39. **Can you edit an applied migration?** No; fix forward.

**Login, lockout and the security log**
40. **How does your login avoid enumeration? Where does it still leak?** Identical 401s and one bcrypt each; only the password holder learns "unverified". A little timing (unmeasured).
41. **Why doesn't a locked account get a "locked" message?** It would reveal the right guess during the lock.
42. **How does lockout survive a failed authentication rolling back?** An event listener writes in `REQUIRES_NEW`; I saw it stay at 0 otherwise.
43. **Lockout under concurrent requests?** SQL increment under the row lock; a conditional lock whose row count names one winner.
44. **Why is the counter driven by events but history by the endpoint?** Every password check must count; Basic authenticates every request.
45. **Your lockout passed a manual run but never locked anyone. How?** A query failing only for real accounts, and a `catch` hiding it. Fail closed.
46. **Isn't lockout a DoS vector?** Yes; auto-unlock limits it; per-IP limits are the fix.
47. **How do you get the client's IP?** The socket; forwarded headers only from a trusted proxy.
48. **How do you make an audit table append-only?** No setters and `@Immutable`, plus a trigger.

**Reset and change**
49. **Design a password-reset flow.** 202 always, stored address, 30 minutes, one live link, validate → consume → hash, everything in one transaction, notify the owner, no auto-login.
50. **Why 30 minutes for reset but 24 hours for verification?** Lifetime follows damage.
51. **What happens to an outstanding reset link after a password change?** Revoked; otherwise it takes the account back.
52. **Why does change-password go through the `AuthenticationManager`?** So guesses count; `matches()` would be an uncounted oracle.
53. **Your change endpoint rejected every correct password and locked users out. Why?** It checked the stored hash as the typed password.
54. **After a password change, what happens on the user's other devices?** Nothing yet; Phase 2 rejects tokens issued before `password_changed_at`.

**Testing**
55. **How do you test security rules, and why as a plain user?** Boundary pairs, the table as data, slice vs integration, mutation checks; the weakest role catches admin-only mistakes.

**Not answerable yet:** the login timing gap (unmeasured) · how much a Java read-modify-write counter loses under 20 parallel guesses (deliberate failure not run) · the measured registration race (`save` vs `saveAndFlush`).

---

## 6. Commands

```bash
./mvnw spring-boot:run                                                   # the app (tests compile, don't run)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--debug | tee target/boot-debug.log   # auto-config report
./mvnw clean test                                                        # the suite (Docker running)

# register → the dev log prints the verification link → verify → log in
curl -s -i -H 'Content-Type: application/json' -d '{"email":"carol@example.com","username":"carol","displayName":"Carol","password":"carol-password-1"}' localhost:8080/api/v1/auth/register
curl -s -i -H 'Content-Type: application/json' -d '{"token":"<TOKEN>"}' localhost:8080/api/v1/auth/verify-email
curl -s -i -H 'Content-Type: application/json' -d '{"email":"carol@example.com","password":"carol-password-1"}' localhost:8080/api/v1/auth/login
curl -s -i -u carol@example.com:carol-password-1 localhost:8080/api/v1/organizations          # Basic; wrong passwords here count too
curl -s -u carol@example.com:<pw> 'localhost:8080/api/v1/users/me/login-history?size=5'
# reset and change
curl -s -i -H 'Content-Type: application/json' -d '{"email":"carol@example.com"}' localhost:8080/api/v1/auth/password-reset/request
curl -s -i -H 'Content-Type: application/json' -d '{"token":"<TOKEN>","newPassword":"a-new-password-1"}' localhost:8080/api/v1/auth/password-reset/confirm
curl -s -i -u carol@example.com:<pw> -X PUT -H 'Content-Type: application/json' -d '{"currentPassword":"<pw>","newPassword":"another-password-2"}' localhost:8080/api/v1/users/me/password
# races: 20 parallel requests, count the status codes (swap in any endpoint and body)
seq 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -d '{"token":"<TOKEN>","newPassword":"pw-number-{}-long"}' localhost:8080/api/v1/auth/password-reset/confirm | sort | uniq -c
# a 76-byte password (19 emoji) for the 72-byte check
P=$(python3 -c 'print("\U0001F600"*19)'); printf '%s' "$P" | wc -c
```

```sql
update user_accounts set role = 'ADMIN' where username = 'alice';                                   -- first admin, by hand
update user_accounts set failed_login_attempts = 0, locked_until = null where username = 'carol';   -- unlock
select purpose, consumed_at is not null as consumed, revoked_at is not null as revoked, expires_at from user_tokens order by id desc limit 10;
select event_type, failure_reason, host(ip_address) as ip, occurred_at from security_events order by id desc limit 15;
```

Inserting a user by hand: hash with `htpasswd -bnBC 10 "" '<pw>' | tr -d ':\n'`, prefix `{bcrypt}`, paste into an **interactive** `psql` (not `psql -c`), id from `nextval('global_id_seq')`.

Reading framework source instead of guessing: `unzip -p ~/.m2/repository/org/springframework/boot/spring-boot-autoconfigure/3.5.16/spring-boot-autoconfigure-3.5.16-sources.jar org/springframework/boot/autoconfigure/security/servlet/UserDetailsServiceAutoConfiguration.java | grep -A3 ConditionalOnMissingBean`.

Investigation switches (dev only, then off again): `org.springframework.security.web.DefaultSecurityFilterChain: DEBUG` (filter list) · `org.springframework.security: TRACE` (one request, filter by filter) · `org.springframework.orm.jpa.JpaTransactionManager: DEBUG` (where commit happens).

---

## 7. Deliberate failures and mutation checks

**Seen** (created on purpose, hit by accident, or verified by experiment):

| Broke | Result |
|---|---|
| Security starter alone | 6 of 13 tests red: slice 401 / 403, integration 401 / 302; generated password in the log |
| Brace pattern in `requestMatchers` · `EndpointRequest.to("health/**")` | Every POST / every GET → 500; startup clean |
| `anonymous()` instead of `permitAll()` | Logged-in caller → 403 on health |
| Auditor using `getName()` | `created_by = alice@example.com` |
| Request record without a masked `toString()`, logged | The plaintext password in the log |
| `@Size(max = 72)` with 19 emoji | 500 from the encoder; `@MaxUtf8Bytes` → clean 400 |
| Spring's default checks, unverified user, wrong password | 403: the state leaks without the password |
| Counter in the login's transaction | Counter stayed 0, no error |
| *(verified on a copy)* Derived `findIdByEmail` + a swallowing listener | `JpaSystemException` for real accounts: lockout would have been silently off |
| *(verified on a copy)* Bulk unlock + entity save; with `clearAutomatically`; with `@DynamicUpdate` | Lock came back / password change lost / both kept |
| *(caught in review)* Email sent inside the registration transaction · the stored hash as the current password | Phantom emails · every change 400 and a self-lockout |

**Not run** (listed with commands in `PHASE_1_REQUIREMENTS.md`): §1.3 registration race (`save` vs `saveAndFlush`) · §1.4 phantom email, self-invocation, check-then-act race, swallowing inside the transaction, prod start without a sender · §1.5 the documented manager bean, lost updates with a Java counter, "locked" after a correct password, history from events, trusting `X-Forwarded-For`, `UPDATE` on `security_events` · §1.6 bulk unlock and `clearAutomatically` in the app, validating after consume, `matches()` instead of the manager.

**Mutation checks: 15/15 caught** (§1.1–§1.2; nothing later has tests):

| Planted | Caught by |
|---|---|
| auth permits `anonymous()` · `denyAll` → `authenticated` · POST restriction dropped · health/info `ADMIN`-only · `health.roles` removed · `/error` permit removed · CSRF on · `/api/v1/**` → `ADMIN` | 1 · 3 · 2 · 6 · 1 · 1 · 11 · 4 tests |
| lock check inverted · auditor `getName()` · `isEnabled()` override removed · `ROLE_` dropped · `Locale.ROOT` dropped · no normalisation in the service | boundary unit test + integration · auditor unit + `createdBy` · unverified unit + integration · authority unit + metrics integration · Turkish-locale test · normalisation unit + case-insensitive login |
| `role` as `ORDINAL` | `ddl-auto: validate` refused every DB-backed context (32 errors) |

---

## 8. Carried debt

**Tests owed** (none before Phase 2, by decision): §1.3 · §1.4 · §1.5 (needs an adjustable `Clock` in the shared test config) · §1.6. The planned lists are in each sub-section's *Tests* in `PHASE_1_REQUIREMENTS.md`. Also owed: the "never echo `rejectedValue`" test from Phase 0, the mutation checks for all of these, and the races as tests.

**Measurements owed:** login timing, known vs unknown email · bcrypt cost 10 vs 12 · the §1.3 registration race.

**Scheduled elsewhere:**
- **Phase 2:** filter-chain 401/403 as `ProblemDetail` (`AuthenticationEntryPoint`, `AccessDeniedHandler`); reject tokens issued before `password_changed_at`; §1.7 profile as a side task.
- **After Phase 2:** OpenAPI (`/v3/api-docs` and `/swagger-ui` permitted in dev only).
- **Phase 9:** async sending (removes most timing leaks); a real `EmailSender` for prod (**prod refuses to start until then**); the cleanup job for expired tokens and 12-month-old events.
- **Phase 10:** rate limiting (email bombing, lockout DoS, per-IP throttling).
- **Phase 11:** forwarded headers behind a proxy.

**Known behaviour, accepted:**
- a double-click on resend can send several emails (only the last link works);
- the 5th wrong password writes `ACCOUNT_LOCKED` just before its `LOGIN_FAILED`;
- `user_tokens` keeps revoked rows until the cleanup job.

**Nits:**
- Slice tests mock the concrete `TaskflowUserDetailsService` (mock the interface), and `class` sits on its own line.
- No comment on the dropped expiry checks in `UserAuthenticationChecks`.
- The 403 detail reads "Email Not Verified".
- `catch (Exception e)` + `instanceof` in `changePassword`; the `uk_user_tokens_active` literal duplicated; no `ValidPassword.message` key; the "password changed" email has no `/forgot-password` link.
- `markEmailVerified` overwrites; `verify` / `resend` would read better in an `EmailVerificationService`.
- The CSRF comment runs two ideas together; the commented `TRACE` line in `application-dev.yml`.
- An unused import in `AuditAwareImpl`; unused `setRole` / `setTimezone`; `Organization`'s no-arg constructor should be `protected` (from Phase 0).
