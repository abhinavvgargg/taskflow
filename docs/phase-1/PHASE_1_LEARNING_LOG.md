# Phase 1 — Revision & Learning Log

> Updated after every sub-phase. **Last updated 2026-10-01, covering §1.1–§1.6** (filter chain · users, passwords, principal, auditor · registration · email verification · login, lockout, login history · password reset and change).
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
| 7 | Duplicate email at registration | **409 `EMAIL_ALREADY_REGISTERED`** | Clear for a person who forgot they have an account. Knowingly lets *this* endpoint reveal registered emails; login and reset must not, and Phase 10 rate limiting blunts probing. Always-202 would need the §1.4 sender first and confuses honest users. |
| 8 | Token storage | **One `user_tokens` table**, `purpose` + `CHECK` | The rules are identical for verification and reset. |
| 9 | Name of the security config / chain bean | `TaskflowSecurityConfig` / `taskflowSecurityFilterChain` | Not `defaultSecurityFilterChain`, which is Boot's own default bean name, and Phase 10 adds a second chain. |
| 10 | Health endpoint access | **Endpoint public, details `ADMIN`-only** | Probes are anonymous; details (DB vendor, disk path) are reconnaissance. Restrict the *details*, not the *endpoint*. |
| 11 | Users table / entity name | **`user_accounts`** / **`UserAccount`** | `user` is reserved in Postgres, and `User` clashes with Spring Security's class. FKs to it will be `user_account_id`. |
| 12 | Credential erasure (`CredentialsContainer`) | **Not implemented** | A plain class's default `toString()` doesn't print the hash, and the stateless context is never stored or serialised. |
| 13 | Creating test users | **`TestUsers`, a plain helper class, not a bean**. ADMIN promotion and locking done with SQL. | Registering a helper bean changes the context configuration and starts a second application + container. SQL mirrors how a real first admin is created. |
| 14 | 72-byte password check | **`@MaxUtf8Bytes(72)`**, a custom constraint in `common/validator` (built in §1.3) | `@Size` counts UTF-16 units, not bytes; the encoder's own check is a 500. Reusable by §1.6's reset and change requests. |
| 15 | Password policy | **At least 12 characters, at most 72 UTF-8 bytes, no composition rules** | Length is what makes passwords strong (NIST SP 800-63B); 12 sits between the old floor of 8 and NIST's newer 15. |
| 16 | Username case | **Any case accepted, lowercased in the service** (`Normalize.normalizeUsername`) | Same rule as the email: `Alice` / `alice` can't be two accounts, and mobile auto-capitalisation doesn't fail sign-ups. |
| 17 | Registration response | **201 with the account body, no `Location`** | There's no `GET /users/{id}` (users can't read each other). A `Location` pointing at an unreadable URL would invite someone to add a user-lookup endpoint. |
| 18 | Race translation | **`saveAndFlush` in the service, translated by constraint name inside `user`**; the generic "read the constraint name" helper (`CommonErrorUtility`) in `common` | The race gets the same specific 409 as the normal path, and `common` still knows nothing about user tables. |
| 19 | §1.3 automated tests | **Deferred** (2026-09-25, user's call, to keep moving) | Recorded as debt: nothing yet protects the §1.3 fixes from regressing. |
| 20 | Token in the email link | **URL fragment**: `{frontend-base-url}/verify-email#token=…` | Never sent to a server, so not in access/proxy logs or `Referer`. The frontend POSTs it. |
| 21 | Old tokens on reissue | **Revoked** (`revoked_at`) + **partial unique index** `uk_user_tokens_active` (one active token per user and purpose). *Changed from "delete" on 2026-09-26.* | The database guarantees one live link and keeps history. Costs: table growth until Phase 9 cleanup; concurrent issues can violate the index (handled in resend). |
| 22 | "Send after commit" | **`RegistrationWorkflow`**, a separate, non-transactional bean: calls the transactional service, then sends | No phantom emails; no transaction held open for mail; no self-invocation. Phase 9 replaces it with `@TransactionalEventListener(AFTER_COMMIT)`. |
| 23 | Token lifetimes | **Typed config** `TokenProperties` in `user/token`: `email-verification-ttl: 24h`, `password-reset-ttl: 30m`, both must be positive; `ttl(purpose)` is an exhaustive `switch` | Per environment, validated at startup; a new purpose without a TTL won't compile. In the feature package so `common` doesn't import `TokenPurpose`. |
| 24 | Email send fails after commit | **Log at ERROR with the account id only, still 201 / 202** | The account exists; resend is the recovery. |
| 25 | `UserToken` → `UserAccount` | **`@ManyToOne(fetch = LAZY)`**, `join fetch` where the account is needed | No eager load on every token read. |
| 26 | `expires_at > created_at` check | **Dropped** (commented out in V3) rather than wiring JPA auditing to the `Clock` | It compared two time sources: `created_at` is real time (auditing), `expires_at` comes from the injected `Clock`. A test clock set in the past would fail the insert. Wiring auditing to the `Clock` remains the principled option. |
| 27 | Token-service transaction rule | **`Propagation.MANDATORY`** on `issue` / `consume` | They refuse to run without the caller's transaction, so a token can't be committed separately from the registration or verification it belongs to. |
| 28 | §1.4 automated tests | **Deferred** (2026-09-27, user's call) | Debt, with the planned list below. |
| 29 | What a failed login reveals (§1.5 D1, 2026-09-27) | **Unknown email, wrong password, locked → the same 401 `AUTHENTICATION_FAILED`. Unverified → 403 `EMAIL_NOT_VERIFIED`, only after a correct password.** Lock stays a pre-check; verification moved to the post-checks. | Spring's default pre-checks reveal locked / unverified from an email alone (verified, and seen in deliberate failure 1). A "locked" answer after a correct password would be a password oracle. |
| 30 | Lockout policy (§1.5 D2) | **5 wrong passwords → 15 minutes; the counter resets when the lock is applied.** Only wrong passwords count; success resets. | Lockout caps guessing on one account; it punishes the owner, so keep it mild. Throttling the attacker is Phase 10. |
| 31 | `security_events` shape (§1.5 D3) | **`inet` IP, all six Phase 1 types in the `CHECK`, `failure_reason` only on `LOGIN_FAILED`, unknown-email failures not recorded, `ON DELETE CASCADE`** | Validated canonical IPs; no migration for §1.6; erasure really erases. |
| 32 | Security-event retention (§1.5 D3) | **12 months**, deleted by Phase 9's cleanup job (stance only) | IPs are personal data. |
| 33 | Append-only events (§1.5 D4) | **`IdentifiedEntity` (id only) split out of `BaseEntity`; `SecurityEvent` is `@Immutable`; a row-level trigger rejects `UPDATE`** | No `updated_*` columns or second clock; `@Immutable` alone ignores changes silently. |
| 34 | Wrong `currentPassword` in §1.6 (§1.5 D5, decided early) | **400 `CURRENT_PASSWORD_INCORRECT`, `field: currentPassword`, counts toward lockout** | 401 would log a Phase 2 client out; counting closes "stolen token + unlimited guesses". |
| 35 | Where the password check lives | **One `DaoAuthenticationProvider` bean** with custom pre/post checks; the `AuthenticationManager` bean is `AuthenticationConfiguration.getAuthenticationManager()` | The single provider bean becomes the global manager's provider, so Basic and `/auth/login` share checks and events (startup log confirms). `new ProviderManager(...)` would have no publisher and leave Basic on an auto-built provider. |
| 36 | How the counter and the log are written | **Counter from authentication events, in `REQUIRES_NEW` (`LoginAttemptService`), no `catch` in the listener (fail closed). `LOGIN_*` recorded by `LoginService` only; `ACCOUNT_LOCKED` on every path, in the lock's transaction; `EMAIL_VERIFIED` in verify's transaction.** | Every password path counts; a failure can't roll the count back; each record commits exactly with what it records. |
| 37 | `SecurityEvent` → user | **Plain `Long userAccountId`** (switched from `@ManyToOne` during review) | A log never navigates to the user; the lockout path only has an id. |
| 38 | Home of `LoginFailedException` | **`common/error`** (the user's choice; the brief said `user`) | It's code-agnostic, like `ResourceConflictException`: a better home than the brief's. |
| 39 | §1.5 tests, mutation checks, timing measurement, deliberate failures 2 and 4–8 | **Deferred** (2026-09-29, user's call, to keep moving) | Debt, with the planned list in §8. |
| 40 | Reset clears the lockout (§1.6 D1, 2026-09-29) | **Yes**: counter 0, `locked_until` null, in the reset's entity write | A reset proves mailbox control; otherwise the correct new password gets 401 for up to 15 minutes. |
| 41 | Reset verifies an unverified email (§1.6 D2) | **Yes**; revokes the active verification link and records `EMAIL_VERIFIED` | The link click is the proof verification asks for. |
| 42 | Reset token lifetime (§1.6 D3) | **30 minutes** | A reset link is a takeover credential; 30 minutes covers slow mail. Verification keeps 24h. |
| 43 | Notify the owner after reset or change (§1.6 D4) | **Yes**, to the stored address, after commit; failures logged, not propagated | The owner learns of a takeover while they can still act. |
| 44 | Whole-row writes on `user_accounts` (§1.6 D5) | **`@DynamicUpdate` on `UserAccount`**; account changes through entity methods | Verified: the default whole-row `UPDATE` undid a bulk unlock; `clearAutomatically` lost the password change; any load-then-save can erase a concurrent lock. |
| 45 | Shape of the password flows | **`PasswordWorkflow` (not transactional) + `PasswordService` (transactional)**, `AccountEmails` for every account email, `@ValidPassword` for the rule, `AccountContact` as the "who to notify" carrier | Send after commit, re-authenticate outside a transaction, one place for links and for the password rule. |
| 46 | §1.6 automated tests | **Not written** (no decision yet: write, or defer like decisions 19, 28, 39) | Debt, with the planned list in §8. |

---

## 1. What we covered

| § | Built | Key artifacts |
|---|---|---|
| **1.1** | Security starter, one filter chain, deny-by-default URL rules, stateless Basic, CSRF and logout off, health details restricted, security tests | `TaskflowSecurityConfig`, `management.endpoint.health.roles`, `TaskflowSecurityConfigTest` (19-row slice), `TaskflowSecurityIntegrationTest` |
| **1.6** | `POST /auth/password-reset/request` (always 202), `POST /auth/password-reset/confirm` (204; unlocks, verifies, records), `PUT /users/me/password` (re-authenticates through the manager, counts toward lockout, revokes the reset link), "password changed" notification, `@DynamicUpdate` | `@ValidPassword` (composed), `PasswordResetRequest`, `PasswordResetConfirmRequest`, `ChangePasswordRequest`, `PasswordService`, `PasswordWorkflow`, `AccountEmails` (verification moved here from `RegistrationWorkflow`), `AccountContact`, `ResetToSend`, `UserAccount.resetPassword`, two error codes; no migration |
| **1.5** | Login (`POST /auth/login`: 200 / 401 / 403), lockout on every password path (5 → 15 min, atomic counter, conditional lock), `security_events` (append-only, `inet`), recording `LOGIN_SUCCEEDED` / `LOGIN_FAILED` / `ACCOUNT_LOCKED` / `EMAIL_VERIFIED`, `GET /users/me/login-history` | `V4__create_security_events.sql`, `IdentifiedEntity`, `UserAuthenticationChecks`, the provider and manager beans in `TaskflowSecurityConfig`, `LockoutProperties`, `LoginRequest`, `LoginService`, `LoginFailedException`, `AuthenticationEventsListener`, `LoginAttemptService`, four `UserAccountRepository` queries, `user/event` (`SecurityEvent`, `SecurityEventType`, `LoginFailureReason`, `SecurityEventRepository`, `SecurityEventRecorder`), `ClientInfo`, `UserController`, `LoginHistoryService`, `LoginHistoryResponse` |
| **1.4** | Email verification: token machinery (generate, SHA-256 at rest, expire, single-use, purpose, revoke on reissue), email sending (dev logs it; prod has none yet), registration sends a link after commit, `POST /verify-email`, `POST /verify-email/resend` (always 202) | `V3__create_user_tokens.sql`, `UserToken`, `TokenPurpose`, `UserTokenRepository` (conditional `UPDATE`s), `TokenCodec`, `UserTokenService`, `TokenProperties`, `IssuedToken`, `RegistrationWorkflow`, `RegistrationResult`, `VerificationToSend`, `VerifyTokenRequest`, `ResendVerificationRequest`, `EmailSender` / `EmailMessage` / `LoggingEmailSender`, `FrontendProperties`, `ResourceInvalidException`; test side: `CapturingEmailSender`, `src/test/resources/application-test.yml` |
| **1.3** | `POST /api/v1/auth/register`: validation (incl. a byte-length constraint), normalisation, duplicate checks, bcrypt, the race translated to the same 409s, 201 with an account summary | `AuthController`, `RegisterRequest` (masked `toString`), `UserAccountResponse`, `UserErrorCode`, `UserRegistrationService`, `MaxUtf8Bytes` + `MaxUtf8BytesValidator`, `ValidationMessages.properties`, `CommonErrorUtility`, `Normalize.normalizeUsername`, repository `existsBy…` |
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
- *(§1.3, verified by manual run)* Anonymous `POST /api/v1/auth/register` → **201** with `{id, email, username, displayName, emailVerified: false, createdAt}` and no `Location`; stored lowercase, `{bcrypt}` hash, `created_by = system`.
- *(§1.3)* Duplicate email in any case → **409 `EMAIL_ALREADY_REGISTERED`** (`field: email`); duplicate username → **409 `USERNAME_TAKEN`**; both → the email code. Bad input → **400** with `fieldErrors`, password never echoed; 19 emoji → 400 with the custom byte message.
- *(§1.3)* A newly registered account can't log in (401) until `email_verified_at` is set; verified by hand with SQL (§1.4 automates it).
- *(§1.4, manual run reported passing 2026-09-27)* Register → the dev log shows `http://localhost:3000/verify-email#token=…` → `POST /verify-email` → **204** → Basic login works. The same token again → **400 `INVALID_TOKEN`**.
- *(§1.4)* Resend (email in any case) → **202** and a new email; the previous token is revoked (→ 400). Verified or unknown email → 202, no email.
- *(§1.4)* Only a 64-character hex hash is stored; tokens end exactly one way (consumed or revoked); at most one active token per user and purpose.
- *(§1.4)* 10 concurrent resends on a new unverified account → 10 × 202 (reported). **Verified in the dev database (2026-09-27):** that account (id 1252) holds **5 tokens: 4 revoked, 1 active**. That's the registration token plus 4 resends that issued; the other **6 requests collided** on `uk_user_tokens_active`, were caught in the workflow, and still answered 202 without sending. Across all 8 tokens: every hash is 64-char hex, no token is both consumed and revoked, and no account has more than one active token.
- *(§1.4, reported)* 20 concurrent verifies with one token → one 204, nineteen 400. (The database can't confirm this one: even a broken check-then-act would leave a single `consumed_at`. Only the HTTP counts show it.)
- *(§1.4)* The first race attempt was run incorrectly and re-run; the numbers above are from the corrected run.

- *(§1.5, reported by the user 2026-09-29)* Lockout works: 5 wrong passwords lock the account; step 6 and 7 manual runs "everything works" (the detailed results weren't sent). Bob (unverified) with a wrong password under Spring's **default** checks → **403** (deliberate failure 1), and **401** once the custom checks were back. With a transactional `LoginService` and the counter `REQUIRED`, the counter **stayed 0** (deliberate failure 3).
- *(§1.5, verified by me)* V4 in a rolled-back transaction (every constraint, the trigger, the index plan, the cascade); both web slices start and pass with the provider bean and a mocked user store (scratch copy, 2026-09-29); the default-vs-custom check behaviour and the entity mapping (details in §4).

- *(§1.6, reported by the user 2026-10-01)* "Ran it, everything works" for the manual-run list (reset, reuse, unlock, verify-by-reset, change-password cases, the old link after a change, the 20-way race). The detailed results weren't sent.
- *(§1.6, verified by me)* `@ValidPassword` reports exactly the old messages (validator run on a scratch copy); the whole-row-write experiments in §4.

**Commits:** `9211684` (§1.1 code) · `56340ae` (docs) · §1.5: `aa7efca` ("1.5 complete", code + docs), `a2cd682` (history-controller fix) · §1.6: **not committed yet** at this update.

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

### §1.3: registration

**Found in code review, before the first run:**

| Bug | What would have happened |
|---|---|
| **Duplicate checks used the raw input**, while the insert used the normalised values | `Alice@Example.com` passed the check, bcrypt ran for nothing, and only the **unique constraint** produced the 409. The normal path was secretly running on the race path. With both email and username taken in mixed case, whichever unique index Postgres checked first decided the code, so `USERNAME_TAKEN` could replace `EMAIL_ALREADY_REGISTERED`. *Fix:* normalise once at the top, and use those values everywhere. |
| **`ValidationMessage.properties`** (singular) | Hibernate Validator only loads **`ValidationMessages.properties`**. The 400 would have read literally `{com.abhinav.taskflow.common.validator.MaxUtf8Bytes.message}`. |
| **200 instead of 201** | `ResponseEntity.ok(...)` on a create. |
| **`UserAccountResponse.from` hard-coded `emailVerified: false`** | Correct at registration, wrong for every verified user once §1.7's `/me` reuses the mapper. |
| **`displayName` with `@Size(min = 3)`** | Rejects real names: "Li", "Jo", 王. |
| **No `field` property on the 409** | The client can't tell which input to highlight. |

**The encoder and 72 bytes, verified (not assumed).** With `@Size(max = 72)` in place of `@MaxUtf8Bytes`, the first attempt with "19 emoji" showed **no error**. The reproduction (a password built with `python3 -c 'print("\U0001F600"*19)'`, byte count checked with `wc -c` → 76, fresh email and username) gave the expected **500**. The first attempt most likely didn't reach the encoder: either a duplicate email or username (checked *before* hashing, so a 409) or an unchanged running app. Running the same passwords against the project's jar and an older one:

| Password | `length()` | UTF-8 bytes | spring-security-crypto **6.5.11** (ours) | **6.3.1** (older) |
|---|---|---|---|---|
| 72 × `a` | 72 | 72 | encoded | encoded |
| 73 × `a` | 73 | 73 | `IllegalArgumentException: password cannot be more than 72 bytes` | encoded |
| 19 × 😀 | **38** | **76** | same exception | encoded |

On 6.3.1, **a different password logs in**: `18 emoji + "first-ending"` was hashed, and `matches("18 emoji + a-totally-different-ending", hash)` returned **true**, because everything after byte 72 is ignored. Current versions refuse instead of truncating. So on our version, `@MaxUtf8Bytes` turns a 500 into a clean 400; on an old version it would be the only guard against silent truncation. Also note: `@Size(max = 72)` counts **UTF-16 units** (an emoji is 2), not characters and not bytes.

**Two temporary deliberate-failure edits were still in the code at wrap-up:** `@Size(max = 72)` instead of `@MaxUtf8Bytes(72)`, and `log.info("Registering {}", registerRequest)`, which still logs the **email** (PII) at INFO even with the password masked. They need reverting before commit. ⚠️ The general lesson: after a deliberate failure, check the diff for leftovers.

### §1.4: email verification

**Found in code review** (most §1.4 bugs never reached a run):

| Bug | What would have happened |
|---|---|
| **The pushed commit didn't compile its tests** | `TestcontainersConfiguration` (committed) referenced `CapturingEmailSender`, which was still untracked. Anyone cloning `main` got a compile error. New files must be staged too. |
| **Compact constructor called the accessor** | `if (!emailVerificationTtl().isPositive())` inside a record's compact constructor: fields are assigned only **after** the body, so the accessor returns `null` → NPE on **every** startup, in every profile (and it was pushed). Use the *parameter*. |
| **Properties classes that couldn't bind** | Plain classes, package-private fields, no setters or constructor parameters → nothing bound → `@NotBlank` / `@NotNull` fail at startup. Records bind through the constructor. |
| **`LoggingEmailSender` without `@Component`** | `@Profile` on a class that's never a bean does nothing; dev would have had no `EmailSender`. |
| **`user_tokens` without a primary key** | `ddl-auto: validate` doesn't check primary keys, so it would never have surfaced. |
| **`token_hash CHAR(64)`** | Expected to fail `ddl-auto: validate` (Hibernate maps `String` to `varchar`; Postgres `char` is `bpchar`). Changed before running, so **not verified**. |
| **Missing constraints and names** | No hex `CHECK` (the guard against storing a raw token), no purpose `CHECK`, an unnamed FK, `idx_` instead of `ix_`. |
| **`common` importing a feature** | `common/config/TokenProperties` imported `user.token.TokenPurpose`. Moved to `user/token`. |
| **Two sources for the TTL** | `TokenPurpose` hard-coded 24h/30m while `TokenProperties` sat unused; expiry computed twice. |
| **The email was sent inside the registration transaction** | The workflow was wired backwards (service → workflow, `MANDATORY`), so the email left **before** the commit. A failed commit → a **phantom email**; a mail failure → the **whole registration rolled back**. Inverted to controller → workflow (no transaction) → service (commits) → send. |
| **"I used `saveAndFlush`, so it's committed"** (a question, not a bug) | Flush sends SQL inside the open transaction; the rows are invisible to other connections and can still roll back. Commit happens when the outermost `@Transactional` method returns. |
| **The link, three rounds** | Relative, then pointing at the **API** path, then a **hard-coded** `http://localhost:3000`. Final: `frontendBaseUrl() + "/verify-email#token=…"`, built in one method. |
| **A `MANDATORY` wrapper called via `this`** | `issueToken()` inside the same class: self-invocation, so the annotation had **no effect**. Removed. |
| **An internal carrier named `…Response`** | It held the raw token; the name invited returning it from a controller. Renamed `RegistrationResult`. |
| **The `expires_at > created_at` check** | Compared two clocks (auditing's real time vs the injected `Clock`). Dropped before V3 was applied. |
| **Resend didn't normalise** | `Alice@Example.com` never found → a silent 202 with no email, undiagnosable for the user. |
| **Resend caught the race *inside* the transaction** | The violation from `saveAndFlush` marks the transaction rollback-only; swallowing it and returning normally → `UnexpectedRollbackException` → 500. Also an inverted `null` check swallowed unnamed violations. Moved to the workflow, outside the transaction. |
| **PII in logs** | The resend failure log wrote the email; §1.3's `log.info("Registering {}")` survived three reviews and a commit before being removed. |

**Rule learned:** inside a transaction, catch a database exception only to **translate** it (throw another). To **continue**, catch it outside, after the transaction has rolled back. Register's catch is fine because it always rethrows.

**V3 is now applied and frozen.** Flyway stores each applied migration's checksum; editing even a comment or whitespace in V3 fails the next startup with a checksum mismatch. Schema changes go in V4.

### §1.5: login, lockout, login history

**Found in review.** Several would have looked fine in a quick manual run:

| Bug | What would have happened |
|---|---|
| **`common/security` imported `user.TaskflowUserDetailsService`** | Broke decision 2 (`common` never imports a feature). And the web slices couldn't start: `@WebMvcTest` never loads `@Service` beans (verified in `WebMvcTypeExcludeFilter`), so the config's dependency had no bean. Fixed: depend on `UserDetailsService`, mock it in the slices. |
| **No exception translation in `/login` yet** | Every wrong password → **500** plus an ERROR stack trace, from `GlobalExceptionHandler`'s catch-all. |
| **Login response built from the principal** | `displayName` / `createdAt` null, `emailVerified` taken from `isEnabled()`, and a 200 with an empty body for an unexpected principal. Fixed: load the account; throw on an unexpected type. |
| **Unknown exceptions wrapped in `RuntimeException`**; **`.get()`** on the lookup | The real type (e.g. a database outage) hidden from logs and future handlers; a bare `NoSuchElementException`. |
| **`findIdByEmail` as a derived query** | The words between `find` and `By` don't pick the selected column: it loaded the whole entity. **Verified on a scratch copy:** an unknown email → `Optional.empty`, an existing one → `JpaSystemException: Result type is 'Long' but the query returned a 'UserAccount'`. Fixed with `@Query("select u.id …")`. |
| **The listener caught `DataAccessException` and logged it** | Together with the previous bug: every wrong password still got 401, but **the counter never moved and nothing ever locked**. Only an ERROR line showed it. Rule: the failure path fails closed (no `catch`). |
| **`@Table("security_events")`** | Doesn't compile: JPA's `@Table` has `name`, not `value` (verified). |
| **Every event factory accepted a `LoginFailureReason`** | `accountLocked(..., BAD_CREDENTIALS)` → `ck_security_events_reason_iff_login_failed` violation (verified): a 500 at the moment an account locks. Fixed: only `loginFailed` takes one, and requires it. |
| **History query: one type, and `OrderBy` in the method name** | Couldn't return three types; two sources of sort, and `PageableFactory`'s default `createdAt` would have been a 500 on an entity without it. |
| **A `PageableFactory.of(page, size, sort)` overload** | Skipped `stabilize()`: the next caller with a non-unique sort gets pages that skip or repeat rows. Removed. |
| **`occuredAt` in the response record** | A misspelt JSON field, free to fix before clients, a breaking change after. |
| **`import java.awt.*`** | An IDE auto-import in a server class. |

**"Deliberate failure 1 still shows 401."** The app hadn't been restarted after commenting out the setters; `spring-boot:run` doesn't reload code. Verified separately, standalone against the project's jars: an unverified account with a wrong password gets `DisabledException` under the default checks and `BadCredentialsException` under ours.

**A mistake in my own notes**: the pre-brief §1.5 notes said to record `ACCOUNT_LOCKED` from `/auth/login` only. A lock caused by a Basic brute force would then be unrecorded. The brief records it on every path.

**V4 is now applied and frozen**, like V1–V3.

### §1.6: password reset and change

**Verified before the brief** (scratch copy, Hibernate 6.6.53): in one transaction, a bulk unlock followed by an entity password change → the entity's whole-row `UPDATE` put the lock back; with `clearAutomatically = true` the password change was lost instead; with `@DynamicUpdate` both survived. Decision 44.

**Found in review:**

| Bug | What would have happened |
|---|---|
| **The current-password check sent `taskflowPrincipal.getPassword()`**, the stored bcrypt hash | bcrypt compared the hash, as a typed password, with itself: **every** change → 400, and each counted as a wrong password, so **five attempts locked the user out** (Basic included). Fixed: `currentPassword()`. The worst bug of the section, and the same family as §1.5's `findIdByEmail`: compiles, looks plausible, fails silently. |
| **`resetPassword` used `Instant.now()`** for `emailVerifiedAt` | Two time sources in one entity write; a fixed test `Clock` wouldn't control it. Fixed: the passed instant. |
| **The confirm request's field was `password`** | The change request used `newPassword`: two names for one concept in the API. Renamed before any client existed. |
| **`confirmReset` returned a `ResetToSend` with a `null` token** | The next caller of `.issuedToken().value()` gets an NPE. Replaced by `AccountContact`. |
| **`applyChange` did nothing when the account was missing** | A 204 and a "password changed" email for a change that never happened. Fixed: `orElseThrow`. |
| **The notification address came from the principal** (`getUsername()`) | Correct value, misleading name (the §1.2 auditor slip); now from the loaded account, like reset. |
| **`.with("field", "newPassword").with("field", "currentPassword")`** | The properties are a map: the second call overwrites the first, so `PASSWORD_UNCHANGED` names the wrong field. And `CURRENT_PASSWORD_INCORRECT` had no field. **Open at this update.** |

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
| *(§1.3)* **Validation before hashing**; duplicate checks before hashing too | Every malformed or duplicate request burning ~100 ms of CPU: a cheap CPU-exhaustion attack on sign-up |
| *(§1.3)* **Byte-length limit (`@MaxUtf8Bytes(72)`) at the boundary** | A 500 from the encoder on current versions; **silent truncation** (a different password logging in) on older ones |
| *(§1.3)* **Masked `toString()` on request records holding secrets** | Plaintext passwords in logs from one careless log line (demonstrated) |
| *(§1.3)* **Normalise once, then check and insert with the same values** | Checks that miss duplicates and leave the constraint to catch them; the wrong 409 code when both fields clash |
| *(§1.3)* **`saveAndFlush` + translation by constraint name, in the feature** | The race returning a different, generic code (and the constraint name) from the normal path |
| *(§1.3)* **409 names the `field`, never echoes the value** | Emails copied into error bodies, client logs and error trackers |
| *(§1.3)* **201 without a `Location` for a resource the caller can't read** | Advertising a URL that doesn't exist, and inviting a user-lookup endpoint |
| *(§1.3)* **Response DTO with only public fields; `emailVerified` derived, not hard-coded** | Hash, lock state or attempts leaking; a mapper that's wrong the day it's reused |
| *(§1.4)* **Tokens from `SecureRandom` (32 bytes), URL-safe Base64 without padding** | Predictable or guessable links (`java.util.Random`, UUIDs) |
| *(§1.4)* **Only the SHA-256 (hex) stored, with a `CHECK` on its format** | Working links in a leaked database or backup; a raw token stored by mistake |
| *(§1.4)* **Consume with one conditional `UPDATE`, branch on the row count** | Double use under concurrency (in §1.6: one reset link applied twice) |
| *(§1.4)* **"Now" from the `Clock`, passed into the query** | Two time sources; expiry untestable |
| *(§1.4)* **One error for every bad token (`INVALID_TOKEN`)** | An oracle revealing which tokens exist or were used |
| *(§1.4)* **Token in a POST body; in links, only in the fragment** | Scanners consuming single-use links; tokens in logs and `Referer` |
| *(§1.4)* **Irreversible side effects after commit, from a non-transactional bean** | Phantom emails; transactions held open during mail; registration lost because mail was down |
| *(§1.4)* **`Propagation.MANDATORY` on the token service** | A token committed separately from its registration or verification |
| *(§1.4)* **Partial unique index: one active token per user and purpose** | Several live links for one account |
| *(§1.4)* **Catch-to-continue only outside the transaction** | `UnexpectedRollbackException` 500s |
| *(§1.4)* **Resend always 202, normalised, sent to the stored address** | A second enumeration oracle; silent non-delivery |
| *(§1.4)* **Dev-only logging sender; none in prod; `@Primary` thread-safe fake in shared test config** | Tokens in production logs; silently lost email; extra test contexts; flaky cross-thread reads |
| *(§1.4)* **Failure logs carry the account id, never the email or link** | PII and credentials in error logs |
| *(§1.4)* **FK index + `ON DELETE CASCADE`** | Sequential scans; a future user deletion blocked by tokens |
| *(§1.5)* **One provider bean with custom checks; the manager from `AuthenticationConfiguration`** | Basic and `/auth/login` checking passwords differently; a manager with a no-op event publisher |
| *(§1.5)* **Locked as a pre-check with the generic 401; unverified as a post-check** | Enumeration of locked / unverified accounts from an email alone; a lock that reveals the right guess |
| *(§1.5)* **Translate only the three expected exceptions** | A database outage reported as "invalid email or password" |
| *(§1.5)* **Counter driven by authentication events** | Basic as an unthrottled side door; any future password path forgetting to count |
| *(§1.5)* **Counter writes in `REQUIRES_NEW`, login flow not transactional** | The rollback trap: a failed login undoing its own count (seen: counter stayed 0) |
| *(§1.5)* **Increment in SQL; lock with a conditional `UPDATE` and its row count** | Lost updates under parallel guesses; two `ACCOUNT_LOCKED` events for one lock |
| *(§1.5)* **The failure listener fails closed** (no `catch`) | Lockout silently off while every response looks normal (it happened: see §2) |
| *(§1.5)* **Conditional reset on success** | A row write on every Basic-authenticated request |
| *(§1.5)* **A record commits with what it records** (attempts alone, state changes with the change) | Events for things that rolled back; attempts lost with the failure they describe |
| *(§1.5)* **IP from the socket (`getRemoteAddr` / `WebAuthenticationDetails`), null when absent** | Forged IPs via `X-Forwarded-For`; `127.0.0.1` recorded for "unknown" |
| *(§1.5)* **`inet`, `CHECK`s, and a trigger on `security_events`** | Garbage IPs; impossible type/reason pairs; silently rewritten audit history |
| *(§1.5)* **Factories that can't build an invalid event** | Constraint violations (500s) at the moment an account locks |
| *(§1.5)* **History: owner from the principal, sort fixed and index-backed, stabilised by `PageableFactory`** | IDOR on other users' IPs; skipped or repeated rows across pages; in-memory sorts |
| *(§1.5)* **No minimum length on the login password, but the 72-byte cap** | Locking out old passwords when the policy tightens; `matches()` comparing only 72 bytes |
| *(§1.6)* **Reset request always 202, link sent to the stored address** | An enumeration oracle; links sent to an address the caller typed |
| *(§1.6)* **30-minute, single-use, purpose-bound reset link in the fragment; one live at a time** | Long-lived takeover credentials in inboxes and logs; scanners consuming links |
| *(§1.6)* **Validate the new password at the boundary, consume the token, then hash** | Links burnt by a typo; a bcrypt per garbage token |
| *(§1.6)* **`@DynamicUpdate` + account changes through entity methods** | A whole-row write undoing a bulk change, or erasing a concurrent lock |
| *(§1.6)* **Change revokes the outstanding reset link** | An earlier link taking the account back after a deliberate change |
| *(§1.6)* **Re-authenticate through the `AuthenticationManager` with the principal's email** | An uncounted oracle for the current password (with a stolen Phase 2 token: unlimited guesses) |
| *(§1.6)* **Notify the owner after reset or change, after commit** | Takeovers discovered days later |
| *(§1.6)* **One password rule (`@ValidPassword`), one place for account emails (`AccountEmails`)** | Reset or change drifting from registration's rule; link building and "never log the token" drifting between workflows |

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

**§1.3 — Registration.**

*The shape:* the organization create flow from Phase 0 (controller → service → repository) plus three security additions: a byte-length password rule, a masked `toString()`, and race translation.

*A custom Bean Validation constraint* is an annotation (`@Constraint(validatedBy = …)`, with `message`, `groups`, `payload`) plus a `ConstraintValidator` class. Hibernate Validator finds the validator through the annotation. `null` counts as valid by convention (presence is `@NotBlank`'s job), so each constraint checks one thing. Custom messages live in **`ValidationMessages.properties`** (plural), keyed by the annotation's `{…}` template.

*When the INSERT runs:* `save()` puts the entity in the persistence context; the SQL runs at flush, normally at commit, **after the service method returns**, so a constraint violation escapes any `try` inside it. `saveAndFlush()` runs it inside the `try`. The exception arrives as Spring's `DataIntegrityViolationException` (translated at the repository proxy), with Hibernate's `ConstraintViolationException` and the constraint name in its cause chain. After a failed flush the transaction is rollback-only: translate and throw.

*Characters, UTF-16 units and bytes are three different counts.* `"😀".length()` is **2** (a surrogate pair), and it's **4** bytes in UTF-8. `@Size` counts `length()`. bcrypt counts bytes.

*Enumeration, both halves:* registration reveals registered emails by design (decision 7); login and reset must never reveal them (§1.5, §1.6).

**§1.4 — Email verification.**

*Secure token design, the six properties:* unguessable (256 bits, `SecureRandom`) · hashed at rest · expiring · single-use · purpose-bound · invalidated on reissue. Plus: never in URLs sent to servers, never in logs outside dev.

*SHA-256 for tokens, bcrypt for passwords:* slow hashing protects **low-entropy** human secrets from guessing. A 256-bit random token can't be guessed at any speed, and a deterministic hash lets the database **find the row by it**; bcrypt's salt makes that impossible.

*A conditional `UPDATE` as a concurrency gate:* `… WHERE used/consumed IS NULL …` is atomic in Postgres. Of two concurrent statements, one updates the row and the other gets **0 rows**. `@Modifying` queries bypass the persistence context (stale entities) and JPA auditing (`updated_at` doesn't move).

*Flush vs commit:* flush = SQL sent inside the open transaction, invisible to other connections, still undoable. Commit = permanent and visible, when the outermost `@Transactional` method returns.

*Rollback-only:* an exception leaving any `@Transactional` method that **joined** the transaction (Spring Data repository methods do) marks the whole transaction rollback-only. Catching it later doesn't clear the mark; a normal return then fails at commit.

*`Propagation.MANDATORY`:* "join the caller's transaction, or refuse to run". Useful for building blocks that must never commit on their own.

*Self-invocation, seen for real:* an annotation on a method called via `this` compiles, looks right, and does nothing.

*Records:* a compact constructor's body runs **before** the fields are assigned, so use the parameters, not the accessors. And every record holding a secret (`IssuedToken`, `VerifyTokenRequest`, `VerificationToSend`, `EmailMessage`) masks it in `toString()`.

*Profile beans and failing fast:* `@Profile("dev")` needs a stereotype (`@Component`) to mean anything. With no `EmailSender` in `prod`, startup fails, which is intended. Tests supply a `@Primary`, thread-safe capturing fake in the **shared** test configuration (no new context), cleared per test.

*Flyway immutability:* once applied, a migration's checksum is recorded; any edit (even a comment) breaks the next startup.

**§1.5 — Login, lockout, login history.**

*The check order* (verified, 6.5.11): load the user (unknown → `BadCredentialsException` after a dummy bcrypt) → **pre-checks** → password → **post-checks**. If a pre-check fails, the password is still compared for timing (`alwaysPerformAdditionalChecksOnUser`, default `true`), but the pre-check's exception wins. Verified standalone against the project's jars:

| Checks | Unverified, wrong password | Unverified, correct password |
|---|---|---|
| Spring's defaults | `DisabledException` | `DisabledException` |
| Ours (locked pre, verified post) | `BadCredentialsException` | `DisabledException` |

*Authentication events:* `ProviderManager` publishes one event per `authenticate()`, synchronously on the request thread. A child manager doesn't re-publish its parent's. `BadCredentialsException` and `UsernameNotFoundException` both map to `AuthenticationFailureBadCredentialsEvent`; locked and disabled get their own events, so they never count. The event carries the name **as typed**.

*Where the manager comes from:* Boot registers a `DefaultAuthenticationEventPublisher` bean; `AuthenticationConfiguration` builds the global manager with it, from the one `AuthenticationProvider` bean if there is one, otherwise from the one `UserDetailsService`. `HttpSecurity`'s manager has no providers of its own and delegates to it. The `WARN` from `InitializeUserDetailsBeanManagerConfigurer` is expected with this setup.

*A record's transaction follows what it records:* an attempt (the count, `LOGIN_FAILED`) commits on its own, so it survives the failure; a state change (`ACCOUNT_LOCKED`, `EMAIL_VERIFIED`) commits with the change. `REQUIRES_NEW` suspends the caller's transaction and takes a second connection; that's why the login flow has none.

*Fail closed:* the listener runs inside `authenticate()`. Its exception becomes the request's 500, which is correct: "count or refuse", never "maybe count".

*Derived queries:* between `find` and `By`, only `Distinct` / `First` / `Top` mean anything. `findIdByEmail` is `findByEmail`. To select a column, write the JPQL.

*Slices:* `@WebMvcTest` loads controllers, advice, filters, converters and security config, never services. A config that needs a service needs a mock in every slice that imports it, and the mock satisfies the dependency only if the config asks for the interface.

*`inet` and `@Immutable`:* Hibernate 6.6 maps `InetAddress` to `inet` with no annotation, and `ddl-auto: validate` accepts it (verified on a scratch copy). `0:0:0:0:0:0:0:1` is stored as `::1`, but `InetAddress.getHostAddress()` prints the long form. `@Immutable` ignores changes without an error (Hibernate's Javadoc), which is why the trigger exists.

*Boot and forwarded headers:* on a detected cloud platform (Kubernetes etc.), forwarded-header handling is on by default (verified in `CloudPlatform`). `getRemoteAddr()` then reflects `X-Forwarded-For` from trusted proxies: decide it in Phase 11.

**§1.6 — Password reset and change.**

*Hibernate writes the whole row* (verified): without `@DynamicUpdate`, an entity's `UPDATE` lists every mapped column from the in-memory copy, so anything another statement changed since the load is overwritten with the stale value. `@DynamicUpdate` lists only the changed columns (`password_changed_at, password_hash, updated_at` in the experiment).

*`clearAutomatically` detaches* (verified): it's for "bulk, then read", not "bulk, then write through an entity". Changes to an entity loaded before the clear are silently never written.

*Constraint composition* (verified): `@ValidPassword` carries `@NotBlank`, `@Size(min = 12)` and `@MaxUtf8Bytes(72)` with `@Constraint(validatedBy = {})`. Each part still reports its own message: empty → "must not be blank" + "must be at least 12 characters"; 19 emoji → the byte message. The part must allow `ElementType.ANNOTATION_TYPE`, which `@MaxUtf8Bytes` didn't until §1.6.

*Re-authentication* asks for the password again before a sensitive change, because sessions (and Phase 2 tokens) get stolen. Doing it through the manager makes the lockout counter cover it.

*Token lifetime follows damage:* verification's worst case is a wrongly verified address (24h is fine); reset's is account takeover (30 minutes).

📊 **Measured:**
- *(§1.6)* **No §1.6 tests** (decision 46). The manual run was reported working (2026-10-01); the race result wasn't sent as numbers.
- *(§1.5)* **No §1.5 tests** (decision 39). V4 verified in a rolled-back transaction: 3 valid rows; 8 invalid inserts rejected by the expected constraint; `UPDATE` rejected by the trigger; an `UPDATE` matching nothing → `UPDATE 0`; the history plan is `Index Scan Backward` with no Sort node; the cascade removed 3 → 0 rows. The web slices pass with the provider bean (scratch copy, 2026-09-29). **Suite count after §1.5 not reported.** Timing (known vs unknown email) **not measured**.
- *(§1.4)* Existing suite still 76/76 after the resend refactor. **No §1.4 tests** (decision 28). Manual runs passing. Resend race: 10 × 202, and the database shows 4 tokens issued and **6 requests handled through the collision path** (verified). Verify race: one 204 (reported).
- *(§1.3)* The existing suite stays green after the §1.3 changes (76/76, including `GlobalExceptionHandler`'s refactor to `CommonErrorUtility`). **No §1.3 tests yet** (decision 19).
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
26. **Does your registration endpoint leak which emails have accounts?** Yes, knowingly: 409 is the clearer experience, and rate limiting (Phase 10) blunts probing. Login and reset give identical answers for known and unknown emails.
27. **Why can't you use `@Size(max = 72)` for a bcrypt password?** `@Size` counts UTF-16 units; bcrypt counts bytes. 19 emoji are 38 units but 76 bytes. On our version the encoder throws (a 500); on older ones everything past byte 72 was silently ignored, so a different password could log in (verified on 6.3.1).
28. **How could a password end up in your logs?** A record's generated `toString()` prints every component, so one `log.info("{}", request)` does it (demonstrated). Override `toString()` on request records holding secrets.
29. **Two people register the same email at the same instant. What happens?** Both can pass the service check. The unique constraint stops the second; `saveAndFlush` makes that happen inside the `try`, so it's translated to the same `EMAIL_ALREADY_REGISTERED`. With plain `save()` it would surface at commit as a generic `RESOURCE_CONFLICT`. *(Reasoned from the code; the 20-way race hasn't been run yet.)*
30. **Why does registration return 201 without `Location`?** There's no URL the caller can read the new user at; advertising one would be wrong and would invite a user-lookup endpoint.
31. **Why normalise before checking for duplicates, not just before saving?** Otherwise the check misses case variants, the constraint does the work, and when two fields clash the wrong error code can win.
32. **Design a password-reset (or verification) token.** 32 random bytes from `SecureRandom`, URL-safe; store only its SHA-256; expiring, single-use (conditional `UPDATE`), purpose-bound, revoked on reissue; carried in a POST body (and in links, only in the fragment); never logged.
33. **Why SHA-256 for tokens but bcrypt for passwords?** Tokens have 256 bits of entropy, so speed doesn't help an attacker, and a deterministic hash is needed to look the row up. Passwords are low-entropy, so they need a slow, salted hash.
34. **How do you make a token single-use under concurrent requests?** One conditional `UPDATE … WHERE consumed_at IS NULL AND revoked_at IS NULL AND expires_at > :now`; exactly one request gets row count 1.
35. **Why is the token in the URL fragment?** Fragments aren't sent to servers: nothing in access logs, proxies or `Referer`. Mail scanners can't consume it either, because consuming needs a POST.
36. **What happens if the mail server is down during registration?** The account is already committed; the failure is logged by account id; the response is still 201; the user uses resend.
37. **Why not send the email inside the transaction?** If the commit fails, the email is a phantom; and the transaction and its connection stay open for the whole send.
38. **What's the difference between flush and commit?** See §4 (§1.4). "I flushed, so it's saved" is wrong.
39. **You caught the exception, so why did the transaction still fail?** The exception left a joined `@Transactional` method (the repository's `saveAndFlush`), which marked the transaction rollback-only; returning normally then fails at commit with `UnexpectedRollbackException`. Catch to continue only outside the transaction.
40. **What does `Propagation.MANDATORY` do, and why use it?** Requires an existing transaction. It keeps token issuing atomic with the registration that needs it.
41. **Does resend reveal which emails exist?** Not by status or body (always 202). It still leaks by **timing** (a known email does work; an unknown one returns at once); Phase 9's async sending removes most of that, and Phase 10's rate limiting stops email-bombing.
42. **Why did your record's compact constructor throw an NPE?** It called the accessor, which reads a field that isn't assigned until the body finishes.
43. **Can you edit a migration that's already been applied?** No. Flyway checks each applied migration's checksum on startup; fix forward with a new version.
44. **How does your lockout survive a failed authentication rolling back?** The counter is written by an event listener, in its own `REQUIRES_NEW` transaction, and the login flow has no transaction. I saw the alternative: with the count in the login's transaction, it stayed at 0.
45. **How does your login avoid user enumeration?** Unknown email, wrong password and locked all give the same 401 and body, and all pay one bcrypt. Only someone with the correct password learns "not verified" (403). It still leaks a little timing: a known email does a few extra writes (not yet measured).
46. **Why doesn't a locked account get a "locked" message?** Shown only after a correct password, it would tell an attacker which guess was right. So "locked" is a pre-check and gets the generic answer.
47. **Isn't lockout a denial-of-service vector?** Yes: anyone who knows your email can lock you out. Auto-unlock limits it; per-IP rate limiting (Phase 10) is the real fix, because it throttles the attacker, not the victim.
48. **How does lockout work under concurrent requests?** An SQL increment under the row lock, then a conditional `UPDATE` "lock where count ≥ 5" that also resets the count; its row count tells exactly one request that it applied the lock.
49. **Why is the counter driven by events, but login history recorded by the endpoint?** Every password check must count, Basic included. But Basic authenticates every request, so recording "logins" from the events would log every API call.
50. **Where does your `AuthenticationManager` come from, and how do Basic and `/login` share it?** One provider bean; `AuthenticationConfiguration` builds the global manager from it with Boot's event publisher; `HttpSecurity`'s manager delegates to it, and `/login` injects it. `new ProviderManager(...)` would have a no-op publisher.
51. **Your lockout passed a manual run but never locked anyone. How?** A derived query that threw only for existing accounts, and a listener that caught the exception. Every response was still a normal 401. Now the failure path fails closed.
52. **When do you use `REQUIRES_NEW`, and what does it cost?** When a record must survive its caller's rollback (a failed-attempt count). It suspends the caller's transaction and takes a second connection, which can exhaust a small pool.
53. **How do you get the client's IP? Why not `X-Forwarded-For`?** The socket address; a header is client-controlled. Behind a proxy, trust forwarded headers only from that proxy (Phase 11), and know Boot turns this on by itself on detected cloud platforms.
54. **How do you make an audit table append-only?** No setters and `@Immutable` in the application, plus a trigger that rejects `UPDATE` in the database, because `@Immutable` ignores changes silently.
55. **Design a password-reset flow.** Request always 202, link to the stored address; the six token properties; the token in the fragment; 30 minutes; one live link; validate, consume, then hash; the change, the unlock, the verification and the events in one transaction; notify the owner after commit; no automatic login.
56. **Why 30 minutes for reset but 24 hours for verification?** Lifetime follows damage: a reset link is account takeover.
57. **What happens to an outstanding reset link when the user changes their password?** It's revoked in the same transaction; otherwise whoever requested it can take the account back.
58. **Why does change-password go through the `AuthenticationManager`?** So a wrong current password counts toward lockout; a plain `matches()` would be an uncounted oracle for anyone holding a session or token.
59. **Your reset unlocked the account, and then the lock came back. How?** A bulk `UPDATE` cleared it, then the entity's whole-row `UPDATE` wrote the stale values back. `@DynamicUpdate` writes only changed columns; `clearAutomatically` would have lost the password instead.
60. **Your change-password endpoint rejected every correct password and locked users out. Why?** It checked the stored hash as if it were the typed password: never a match, and each failure counted.
61. **Does your reset request reveal which emails exist?** Not by status or body; by timing, yes (a known email writes a token and sends mail). Phase 9's async sending removes most of it.

### Not answerable yet

- The timing gap between a known and an unknown email at login (measure it). — §1.5 debt
- How much the Java read-modify-write counter loses under 20 parallel guesses. — §1.5 deliberate failure 4
- The measured outcome of the registration race (`save` vs `saveAndFlush`). — §1.3 debt

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

Registration and the 72-byte check (§1.3):

```bash
curl -s -i -H 'Content-Type: application/json' \
  -d '{"email":"Carol@Example.com","username":"Carol","displayName":"Carol","password":"carol-password-1"}' \
  localhost:8080/api/v1/auth/register                                    # 201; stored lowercase
P=$(python3 -c 'print("\U0001F600"*19)'); printf '%s' "$P" | wc -c      # 76 bytes, 38 UTF-16 units
```

```sql
update user_accounts set email_verified_at = now() where username = 'carol';   -- verify by hand until §1.4
```

Email verification (§1.4):

```bash
curl -s -i -H 'Content-Type: application/json' -d '{"token":"<TOKEN>"}' localhost:8080/api/v1/auth/verify-email          # 204, then 400 on reuse
curl -s -i -H 'Content-Type: application/json' -d '{"email":"CAROL@example.com"}' localhost:8080/api/v1/auth/verify-email/resend   # always 202
# double-click races
seq 10 | xargs -P 10 -I{} curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -d '{"email":"<unverified email>"}' localhost:8080/api/v1/auth/verify-email/resend | sort | uniq -c
seq 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -d '{"token":"<TOKEN>"}' localhost:8080/api/v1/auth/verify-email | sort | uniq -c
```

```sql
select purpose, length(token_hash), consumed_at is not null as consumed, revoked_at is not null as revoked from user_tokens order by id;
```

Watching flush vs commit: `logging.level.org.springframework.orm.jpa.JpaTransactionManager: DEBUG` shows where *"Initiating transaction commit"* falls relative to the email log line.

Login, lockout, history (§1.5):

```bash
curl -s -i -H 'Content-Type: application/json' -d '{"email":"carol@example.com","password":"wrong"}' localhost:8080/api/v1/auth/login      # 401; the 5th locks
curl -s -i -u carol@example.com:wrong localhost:8080/api/v1/organizations                                                               # the same counter, through Basic
curl -s -u carol@example.com:<password> 'localhost:8080/api/v1/users/me/login-history?size=5&page=0'                                      # own history, newest first
# 20 parallel wrong passwords (deliberate failure 4, and the race)
seq 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -d '{"email":"carol@example.com","password":"wrong-{}"}' localhost:8080/api/v1/auth/login | sort | uniq -c
```

```sql
select failed_login_attempts, locked_until from user_accounts where username = 'carol';
update user_accounts set failed_login_attempts = 0, locked_until = null where username = 'carol';   -- unlock by hand
select id, user_account_id, event_type, failure_reason, host(ip_address) as ip, left(user_agent, 20) as ua, occurred_at
from security_events order by id desc limit 15;
```

Password reset and change (§1.6):

```bash
curl -s -i -H 'Content-Type: application/json' -d '{"email":"carol@example.com"}' localhost:8080/api/v1/auth/password-reset/request              # 202 always
curl -s -i -H 'Content-Type: application/json' -d '{"token":"<TOKEN>","newPassword":"a-new-password-1"}' localhost:8080/api/v1/auth/password-reset/confirm   # 204, then 400
curl -s -i -u carol@example.com:<pw> -X PUT -H 'Content-Type: application/json' -d '{"currentPassword":"<pw>","newPassword":"another-password-2"}' localhost:8080/api/v1/users/me/password
seq 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -d '{"token":"<TOKEN>","newPassword":"a-new-password-{}"}' localhost:8080/api/v1/auth/password-reset/confirm | sort | uniq -c
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
| *(§1.3)* `RegisterRequest` without a `toString()` override, logged with `log.info("Registering {}", request)` | **The plaintext password in the app log** |
| *(§1.3)* `@Size(max = 72)` instead of `@MaxUtf8Bytes(72)`, 19 emoji (76 bytes) | **500**, `IllegalArgumentException: password cannot be more than 72 bytes` from the encoder. With `@MaxUtf8Bytes` restored: a clean 400. (A first attempt showed no error, most likely a duplicate stopping it before hashing, or a stale app.) |
| *(§1.3, not run yet)* 20 concurrent registrations, same email, `save()` vs `saveAndFlush()` | Expected: generic `RESOURCE_CONFLICT` responses with `save()`, none with `saveAndFlush()` |
| *(§1.4, happened in the code, caught in review)* Email sent inside the registration transaction | Not observed at runtime; fixed before a failing commit could show the phantom email |
| *(§1.5)* Spring's default checks, unverified bob with a **wrong** password | **403 `EMAIL_NOT_VERIFIED`**: the account's state leaks without the password. With the custom checks: 401. (A first attempt showed 401 because the app hadn't been restarted.) |
| *(§1.5)* The rollback trap: `@Transactional` login, counter `REQUIRED` | **Counter stayed 0** after the wrong passwords, with no error anywhere |
| *(§1.5, happened in the code, caught in review)* A derived `findIdByEmail` plus a listener that swallowed `DataAccessException` | Verified on a scratch copy: `JpaSystemException` for every existing account. With the `catch`, lockout would have been silently off. |
| *(§1.6, run by me on a scratch copy for the brief)* Bulk unlock + entity password change in one transaction; then with `clearAutomatically`; then with `@DynamicUpdate` | Lock came back / password change lost / both survived (decision 44) |
| *(§1.6, happened in the code, caught in review)* The stored hash sent as the current password | Would have been: every change 400, and the user locked out after five tries |
| *(§1.6, not run)* Deliberate failures 1, 2 (bulk unlock, `clearAutomatically`), 4 (validate after consume), 5 (`matches` instead of the manager); 3 (old link after a change) was in the reported manual run | Listed in `PHASE_1_REQUIREMENTS.md` §1.6 |
| *(§1.5, deferred 2026-09-29)* The documented manager bean (2) · lost updates with a Java counter (4) · "locked" after a correct password (5) · history from the events (6) · trusting `X-Forwarded-For` (7) · `UPDATE` on `security_events` (8) | Listed in `PHASE_1_REQUIREMENTS.md` §1.5, deliberate failures |
| *(§1.4, optional, not run)* The phantom email on purpose · self-invocation · check-then-act consume (20-way race) · swallowing inside the transaction (10-way resend → `UnexpectedRollbackException`) · prod start without a sender | Listed in `PHASE_1_REQUIREMENTS.md` §1.4, deliberate failures |

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

- **§1.6 open review items (2026-10-01):** `PASSWORD_UNCHANGED` sets `field` twice (the map keeps `currentPassword`; should be `newPassword`); `CURRENT_PASSWORD_INCORRECT` has no `field`. Nits: `catch (Exception e)` + `instanceof` in `changePassword`; the `"uk_user_tokens_active"` literal duplicated in `PasswordWorkflow`; no `ValidPassword.message` key; the "password changed" email has no `/forgot-password` link.
- **§1.6 tests (decision 46)**: none. Planned (table in `PHASE_1_REQUIREMENTS.md` §1.6): `@ValidPassword` boundaries; masked request records; `resetPassword`; `PasswordService` order (consume before hash) and revokes; `PasswordWorkflow` (equality before the manager, exception mapping, no email on failure); a JPA test proving `@DynamicUpdate` keeps a bulk change; integration for request (email only for known accounts), confirm, reuse, unlock, verify, change and the revoked link; the 20-way race.
- **§1.5 tests (decision 39)**: none. Planned (full table in `PHASE_1_REQUIREMENTS.md` §1.5): an **adjustable `Clock`** in the shared test config; unit tests for the provider's checks, `LoginAttemptService`, `LoginService`'s translation and recording, `ClientInfo`; JPA tests for the four counter queries, the `inet` round trip, the `CHECK`s, the trigger, history paging; integration tests for the rollback trap, the Basic side door, unlock by clock, identical 401 bodies, 403 for unverified, reset on success, history ownership and "no rows from Basic"; the 20-way race.
- **§1.5 mutation checks, timing measurement (known vs unknown email), and deliberate failures 2 and 4–8**: not run (decision 39).
- **§1.5 suite count**: not reported after §1.5 (last confirmed 76/76 at §1.4; the two slices pass).
- **Nits (§1.5):** the slice tests mock the concrete `TaskflowUserDetailsService` (a `common/security` test imports `user`; mock `UserDetailsService`), and `class` sits on its own line in `TaskflowSecurityConfigTest`; `UserAuthenticationChecks` needs a comment saying the default expiry checks were dropped on purpose; the 403 detail text is "Email Not Verified".
- **Known ordering (§1.5):** the 5th wrong password writes `ACCOUNT_LOCKED` a moment before its `LOGIN_FAILED`, because the listener runs inside `authenticate()`. Newest-first history shows the failure above the lock.
- **§1.4 tests (decision 28)**: none. Planned: `TokenCodec` (43 URL-safe characters, distinct tokens, 64-hex deterministic hash); `UserTokenService` with a fixed `Clock` (revoke then insert, hash never raw, 0 rows → `INVALID_TOKEN`, malformed input rejected before hashing); `RegistrationWorkflow` (sends after the service returns, not on failure, survives a sender exception, swallows only `uk_user_tokens_active`); JPA slice (conditional `UPDATE` 1 then 0, expiry boundary, wrong purpose, hex `CHECK`, partial unique index, cascade); integration with `CapturingEmailSender` (register → token from the inbox → verify → login; reuse → 400; resend revokes; unknown/verified → 202 with no email).
- **§1.4 deliberate failures**: not run (list in §7).
- **Resend leaks by timing**, and can be used to flood an inbox: Phase 9 (async) and Phase 10 (rate limiting).
- **A double-click on resend can send several emails**, of which only the last link works. Acceptable; documented.
- **`user_tokens` grows** (revoked tokens are kept): `deleteExpiredBefore` exists, unused, for Phase 9's cleanup job.
- **Nits (§1.4):** `markEmailVerified` overwrites the timestamp if called twice (`if (emailVerifiedAt == null)` would keep the first); `verify` / `resend` live in `UserRegistrationService` (an `EmailVerificationService` would name them better).
- **§1.3 tests (decision 19)**: none written. Planned: the validator (72/73 bytes, emoji, `null`), masked `toString()`, the service with fakes (normalise before checks, nothing saved on a duplicate, hash never raw, race translation), a web slice (201, 400 without the password echoed, 19 emoji → 400), and integration (stored form, duplicates in any case, unverified → 401). This also leaves the Phase 0 "never echo `rejectedValue`" debt without its test.
- **§1.3 deliberate failure #3 (the 20-way race)**: not run. The command is in `PHASE_1_REQUIREMENTS.md` §1.3.
- **401/403 bodies are Boot's error JSON, not `ProblemDetail`**: Phase 2 (`AuthenticationEntryPoint`, `AccessDeniedHandler`).
- **OpenAPI**: still deferred; do it after Phase 2 so the security scheme is documented once. `/v3/api-docs` and `/swagger-ui` must be permitted in `dev` only.
- **The CSRF comment** in `TaskflowSecurityConfig` runs two ideas together. Reword.
- `org.springframework.security: TRACE` is left **commented** in `application-dev.yml`. Harmless; delete when tidying.
- 📊 **bcrypt timing** at cost 10 vs 12: not measured yet.
- **Nits in §1.2 code (still there):** unused `UserDetails` import in `AuditAwareImpl`; the private `setRole` / `setTimezone` in `UserAccount` are unused (the constructor assigns directly).
- **README**: auth endpoints and the Basic-auth note are due by the end of Phase 1 (definition of done).
- From Phase 0, still open: the `Organization` no-arg constructor should be `protected` (`UserAccount`'s already is).
