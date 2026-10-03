# Phase 1 — Users & Auth Core (Requirements)

> Companion to `../PROJECT_CONTEXT.md` · decisions and traps from `../phase-0/PHASE_0_LEARNING_LOG.md` · test patterns from `../phase-0/TESTING_GUIDE.md`. Requirements only, no code. Tick the boxes as you go.
> **Time-box: 7h, tests included. Hard stop at 10.5h (150%).** Anything unfinished becomes a side task in Phase 2. The phase doesn't get extended.

## ▶ Where we are (resume here) — Phase 1 closed 2026-10-01

| | |
|---|---|
| **Done** | §1.1 security starter & filter chain (`9211684`, tests ✅) · §1.2 users, passwords, principal, auditor (`1fc0f54`, tests ✅) · §1.3 registration (`02bc36b`, **tests deferred**) · §1.4 email verification (`5085ed7` and earlier partial commits, **tests deferred**) · §1.5 login, lockout, login history (`aa7efca`, `a2cd682`, **tests deferred**) · §1.6 password reset & change (`63bb1b4`, `4e115a2`, **tests deferred**) · §1.7 **moved to Phase 2** (decision 28) |
| **Next** | **Phase 2 — JWT & sessions**, briefed 2026-10-01 in `../phase-2/PHASE_2_REQUIREMENTS.md` (decisions taken 2026-10-02; D9 defers this phase's test debt until Phase 2 closes). Side task: §1.7 profile (decision 28). Before starting: your 15-minute Phase 1 notes (bottom of this doc). |
| **Settled at close (2026-10-01)** | No deferred tests before Phase 2 · §1.7 moved to Phase 2 · email change to the backlog. The learning log was consolidated by theme; sub-section detail stays in this doc. |
| **Carried forward** | Nothing blocks Phase 2. `password_changed_at` is stamped by reset and change, ready for Phase 2's "reject tokens issued before it". |
| **Open debt** | See `PHASE_1_LEARNING_LOG.md` §8: **§1.3–§1.6 tests** (planned lists there), §1.5 mutation checks and timing measurement, deliberate failures not run (§1.3 race, §1.4 list, §1.5 #2 and #4–8, §1.6 #1, #2, #4, #5), resend timing leak, bcrypt timing not measured, small nits |
| **Environment state** | Dev DB has migrations **V1–V4** applied (**all frozen**: never edit an applied migration). Dev users include `alice` (ADMIN, verified), `bob` (unverified), `carol` (verified), plus accounts from manual runs; `security_events` holds rows from the §1.5 manual runs, and carol may be locked or have a non-zero counter (reset: `update user_accounts set failed_login_attempts = 0, locked_until = null where username = 'carol';`). Suite: last confirmed 76/76 at §1.4; not re-run after §1.5 (the two web slices pass). |
| **Numbering note** | This doc's Decisions table (1–28) and the learning log's decisions (1–47) are numbered **independently**; the learning log is the complete record. |

---

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

## Decisions (recorded from 2026-09-24; latest 2026-09-27)

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
| 12 | **Old tokens on reissue** (§1.4) | **Revoked** (`revoked_at` set), plus a **partial unique index**: at most one active token per user and purpose. *Changed 2026-09-26 from "deleted".* | The database itself guarantees only one live link, and the rows keep their history. Costs: the table grows until Phase 9's cleanup job (`deleteExpiredBefore` exists for it), and two concurrent issues for one user can violate the index, so resend must turn that into its normal 202. |
| 13 | **"Send after commit"** (§1.4) | **A separate, non-transactional `RegistrationWorkflow` bean** | Explicit and testable; avoids self-invocation. Phase 9 replaces it with `@TransactionalEventListener(AFTER_COMMIT)`. |
| 14 | **Email verification token lifetime** (§1.4) | **24 hours** (typed config) | Long enough for "I'll do it tonight"; the token is single-use and purpose-bound. |
| 15 | **Email fails after the account is saved** (§1.4) | **Log at ERROR (account id only), still 201** | The account exists; resend is the recovery path. Rolling back would need the send inside the transaction, which brings back the phantom email. |
| 16 | **`UserToken` → `UserAccount`** (§1.4) | **`@ManyToOne(fetch = LAZY)`** | A real association, without `@ManyToOne`'s default eager load on every token read. |
| 17 | **What a failed login reveals** (§1.5 D1), decided 2026-09-27 | **Unknown email, wrong password, locked → the same 401 `AUTHENTICATION_FAILED`** (identical bodies). **Unverified → 403 `EMAIL_NOT_VERIFIED` only after a correct password.** Lock stays a pre-check; verification moves to the post-checks. | Spring's default pre-checks reveal locked / unverified from an email alone (verified in 6.5.11). "Locked" shown only after a correct password would be a password oracle. All-generic would strand users who never verified. |
| 18 | **Lockout policy** (§1.5 D2), decided 2026-09-27 | **5 wrong passwords → locked 15 minutes** (typed config). **The counter resets to 0 when the lock is applied.** Only wrong passwords count; success resets. | Lockout punishes the owner, not the attacker; it only caps guessing speed. Keep-counting and escalating locks make a denial of service worse without stopping it. Throttling the attacker is Phase 10. |
| 19 | **`security_events` shape** (§1.5 D3), decided 2026-09-27 | **`inet` IP · all six Phase 1 event types in the `CHECK` · `failure_reason` present exactly on `LOGIN_FAILED` · unknown-email failures not recorded · `ON DELETE CASCADE`** | Validated, canonical IPs; no migration in §1.6; history can say why a login failed; no ownerless rows or non-users' typos stored; erasure actually erases. |
| 20 | **Security-event retention** (§1.5 D3), decided 2026-09-27 | **12 months, then deleted by Phase 9's cleanup job** (stance only, nothing built) | IP addresses are personal data (GDPR storage limitation); 12 months covers investigating a compromised account. |
| 21 | **Append-only `security_events`** (§1.5 D4), decided 2026-09-27 | **Id-only `@MappedSuperclass` split out of `BaseEntity`; `SecurityEvent` extends it, is `@Immutable`; a row-level trigger rejects every `UPDATE`** | No meaningless `updated_*` columns and no second clock. `@Immutable` alone ignores changes silently (Hibernate Javadoc); the trigger makes any writer's mistake loud. |
| 22 | **Wrong `currentPassword` on password change** (§1.6, decided early in §1.5 D5, 2026-09-27) | **400 `CURRENT_PASSWORD_INCORRECT`, `field: currentPassword`; counts toward lockout** (checked through the same `AuthenticationManager`) | Not 401: a Phase 2 client would log the user out. 400 over 403: it's one wrong form field. Counting closes the "stolen token + unlimited current-password guesses" path. |
| 23 | **Reset clears the lockout** (§1.6 D1), decided 2026-09-29 | **Yes: counter to 0 and `locked_until` cleared, in the reset's entity write** | A reset proves mailbox control, stronger than any guess; otherwise the correct new password gets 401 for up to 15 minutes. |
| 24 | **Reset verifies an unverified email** (§1.6 D2), decided 2026-09-29 | **Yes; also revokes the active verification token and records `EMAIL_VERIFIED`** | Clicking a link sent to the address is the proof verification asks for; otherwise a second email and a 403 after a successful reset. |
| 25 | **Reset token lifetime** (§1.6 D3), decided 2026-09-29 | **30 minutes** (the configured `password-reset-ttl`) | Covers slow mail; a reset link is a takeover credential, so exposure stays short. Verification keeps 24h: its worst case is far milder. |
| 26 | **Notify the owner after a reset or change** (§1.6 D4), decided 2026-09-29 | **Yes: "your password was changed" to the stored address, after commit; a failure is logged, not propagated** | The owner learns of a takeover while they can still act (OWASP forgot-password guidance). |
| 27 | **Whole-row writes on `user_accounts`** (§1.6 D5), decided 2026-09-29 | **`@DynamicUpdate` on `UserAccount`; every §1.6 account change through entity methods** | Verified: without it, the entity write undoes a bulk unlock; `clearAutomatically` loses the password change; and any load-then-save flow can erase a concurrent lock. |
| 28 | **§1.7 (profile)**, decided 2026-10-01 | **Moved to Phase 2 as a side task** | Phase 1 is past its time-box and §1.7 was first on the trim list; the phase rule sends unfinished work to Phase 2. Nothing in Phase 2 depends on it. |

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

§1.1–§1.6 are written this way; §1.7 onward get the same treatment when we reach them.

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

## 1.4 — Email verification ✅ built (tests deferred; decision 12 changed to revoke; see the learning log)

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
                      │            delete this user's older unused tokens of the same purpose   ← superseded: REVOKED (decision 12)
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
     0 rows → 400 INVALID_TOKEN  (unknown, expired, already used, wrong purpose: one answer)

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
| **Issuing a new token deletes the old unused ones** (same user, same purpose). *Superseded 2026-09-26: they're **revoked**, and a partial unique index allows one active token (decision 12).* | Only the latest link works; the table doesn't grow per resend |
| **Consume with one conditional `UPDATE` and check the row count** | Two clicks at the same instant can't both succeed. Harmless for verification, but in §1.6 it would mean one reset link used twice. |
| **"Now" from the `Clock`, passed into the `UPDATE`** | One time source, so expiry is testable with a fixed clock |
| **One error for every bad token: `400 INVALID_TOKEN`** | The client's next step is the same in every case (ask for a new link), and no hint of which tokens exist |
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

💡 **Flush is not commit** (asked 2026-09-26). `saveAndFlush` sends the SQL to Postgres **inside the open transaction**: the rows are invisible to every other connection (READ COMMITTED) and can still be rolled back. The **commit** happens when the outermost `@Transactional` method returns. So "I flushed, so it's saved" is wrong, and an email sent after a flush but before the method returns is still a phantom-email risk. See it: `logging.level.org.springframework.orm.jpa.JpaTransactionManager: DEBUG` prints the email line **before** *"Initiating transaction commit"*; or pause on a breakpoint after the send and query `user_accounts` from `psql`: the new row isn't there yet. (§1.3 used `saveAndFlush` to make constraint violations surface inside the `try`: that's about *when errors appear*, not durability.)

💡 **URL fragments.** Everything after `#` stays in the browser. It isn't sent in the request, so it isn't in server or proxy logs and isn't in the `Referer` header. The frontend's JavaScript reads it and POSTs the token.

💡 **Profile-specific beans, and failing fast.** `@Profile("dev")` puts the logging sender only in dev. With no `EmailSender` in prod, Spring can't satisfy the dependency and **refuses to start**. That's a feature. Tests need their own implementation, or every test context fails.

💡 **`@ManyToOne` defaults to `EAGER`.** If `UserToken` references `UserAccount`, mark it `fetch = LAZY`. Otherwise loading any token also loads its user, every time. It's a quiet N+1 seed for Phase 8.

### Decisions for this section: all six recommendations accepted (2026-09-26)

| # | Decision | Chosen |
|---|---|---|
| a | Where the token goes in the email link | **URL fragment** (`#token=`), not the query string |
| b | Old unused tokens when a new one is issued | **Delete them** (not mark as revoked). *Changed 2026-09-26 to **revoke** + partial unique index: see decision 12.* |
| c | How "send after commit" is structured | **A separate non-transactional `RegistrationWorkflow` bean** calling the transactional service, then the sender |
| d | Email verification token lifetime | **24 hours** (long enough for "I'll do it tonight"; the link is single-use and purpose-bound) |
| e | Email send fails after commit | **Log at ERROR (the account id, never the token), still return 201**; resend is the recovery |
| f | `UserToken` → `UserAccount` | **`@ManyToOne(fetch = LAZY)`** to the entity (vs a plain `Long` id column) |

### Requirements

**Configuration:**
- [x] Typed properties (a record, like `ApiProperties`): `TokenProperties` (`Duration`, `@NotNull`) and `taskflow.app.frontend-base-url` (`@NotBlank`). Validated at startup.

**Email sending** (`common/mail`):
- [x] `EmailSender` interface: send one message (to, subject, body). A small `EmailMessage` record.
- [x] `LoggingEmailSender`, **`@Profile("dev")` only**: logs recipient, subject and body at INFO. ⚠️ A **deliberate, profile-bounded exception** to "no tokens in logs": write that down in a comment.
- [x] No production implementation yet; a prod start must fail.
- [x] In tests: a capturing `EmailSender` (keeps sent messages in memory, with a way to read the latest one for an address and to clear them), registered in the **shared** `TestcontainersConfiguration`.

**Migration `V3__create_user_tokens.sql`:**
- [x] `id`, `user_account_id` (FK to `user_accounts`, **`ON DELETE CASCADE`**), `purpose` (`CHECK` in `EMAIL_VERIFICATION`, `PASSWORD_RESET`), `token_hash` (**unique**, and a `CHECK` that it's exactly 64 lowercase hex characters), `expires_at`, `used_at` (nullable), audit columns.
- [x] **`ix_user_tokens_user_account_id`**: Postgres won't create it for you. 📌 From now on, every FK gets its index.

**Tokens** (`user` package):
- [x] `TokenPurpose` enum; `UserToken` entity (`@Enumerated(STRING)`, `@ManyToOne(fetch = LAZY)`, protected constructor, a factory).
- [x] Token generation and hashing in one small class: 32 bytes from **one shared `SecureRandom`**, Base64URL **without padding** → the raw token; SHA-256 of its UTF-8 bytes → **lowercase hex**.
- [x] `VerificationTokenService` (or `UserTokenService`, since §1.6 reuses it):
  - `issue(account, purpose)`: delete that user's unused tokens of the purpose, save the new hash with `expires_at = now + ttl`, **return the raw token** (the only place it exists).
  - `consume(rawToken, purpose)`: hash it, run the conditional `UPDATE` with `now` from the `Clock`; 1 row → return the account id; 0 rows → `400 INVALID_TOKEN`.
- [x] Repository: the conditional `UPDATE` and the `DELETE` as `@Modifying` queries returning a row count.
- [x] `INVALID_TOKEN` (400) added to `UserErrorCode`.

**Registration, extended:**
- [x] `UserRegistrationService.register` also issues the token, in the **same** transaction, and returns the account *and* the raw token.
- [x] New `RegistrationWorkflow` (not transactional, **a separate bean**): calls the service, **then** sends the email. If sending throws: log at ERROR with the account id, still return normally.
- [x] `AuthController` calls the workflow. Still 201 with the same body; the token **never** appears in the response.

**Verify and resend:**
- [x] `POST /api/v1/auth/verify-email` `{token}` → **204**; invalid → **400 `INVALID_TOKEN`**. Request record with `@NotBlank` and a **masked `toString()`**.
- [x] Verifying sets `email_verified_at` (from the `Clock`) through `markEmailVerified`.
- [x] `POST /api/v1/auth/verify-email/resend` `{email}` → **always 202**. Only for an existing, unverified account: issue a new token and send after commit (through the workflow). Normalise the email.
- [x] Both endpoints are already permitted anonymously by the §1.1 rules; nothing to change there.

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
| **Swallowing inside the transaction.** Put a `try/catch` for the `uk_user_tokens_active` violation **inside** `@Transactional resend()` and return normally; run the 10-way double-click resend | Some requests → **500** with `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only` | An exception leaving any `@Transactional` method that joined the transaction (here `saveAndFlush`) marks it rollback-only; catching it later doesn't unmark it. **Catch to translate inside; catch to continue only outside** (in the workflow). Register's catch is fine because it always rethrows. |
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
| **Unit** | Token generation: 43 URL-safe characters, no padding, two tokens differ · hashing: deterministic, 64 lowercase hex, never equal to the raw token · `VerificationTokenService` with a fixed `Clock`: `issue` deletes old tokens and stores the hash (never the raw token); `consume` passes the clock's instant and maps 0 rows → `INVALID_TOKEN` · `RegistrationWorkflow`: sends **after** the service returns, doesn't send if the service throws, still returns if the sender throws · request `toString()` masks the token |
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

## 1.5 — Login, lockout, login history ✅ built (`aa7efca`, `a2cd682`; **tests, mutation checks, timing and deliberate failures 2, 4–8 deferred**, see the learning log)

> **Estimate, honestly:** the plan says ~1h 45m. This section has more moving parts than §1.4 (a provider, a listener, a new table, two endpoints), so **~3h is realistic**. If it runs over, trim in the phase's order: the login-history **endpoint** goes first (keep **recording** the events).
> **Carried in from §1.4:** record `EMAIL_VERIFIED` from the verify flow once `security_events` exists.
> **Rewritten from the old notes (2026-09-25).** Everything in them is kept below: the traps are in *Traps*, the two old deliberate failures are in *Deliberate failures*. One old note was **wrong, and it was mine**: "record `ACCOUNT_LOCKED` from `/auth/login` only" would leave a lock caused by a **Basic** brute force unrecorded, which is the event you'd most want. It's now recorded on every path (see *How*).

### 🏗️ Decisions ✅ all five recommendations accepted 2026-09-27 (Decisions table rows 17–22; D5 decided now, not deferred)

**Verified before writing these** (Spring Security 6.5.11 and Boot 3.5.16 sources in `~/.m2`, Hibernate 6.6.53, the dev Postgres 17): the order of the pre-checks, the password check and the post-checks; which event each failure publishes; how the global `AuthenticationManager` is assembled and gets its event publisher; the `inet` mapping; the V4 SQL (rolled back). What's inferred rather than verified is marked where it's used.

#### D1 — What a failed login reveals

| Situation | A. Everything generic | B. Spring's default checks | **C. Recommended** |
|---|---|---|---|
| Unknown email, or wrong password | 401 | 401 | **401 `AUTHENTICATION_FAILED`** |
| Locked account (any password) | 401 | a different answer (`LockedException`), **without the password** | **the same 401, the same body** (lock stays a **pre**-check) |
| Unverified, wrong password | 401 | a different answer (`DisabledException`), **without the password** | **the same 401** |
| Unverified, **correct** password | 401 | as above | **403 `EMAIL_NOT_VERIFIED`** (verification moves to the **post**-checks) |

**Why C beats B.** Verified in `AbstractUserDetailsAuthenticationProvider`: the default pre-checks throw `LockedException` / `DisabledException` **before the password decides anything**. The password is still compared, for timing (`alwaysPerformAdditionalChecksOnUser`, default `true`), but the result is thrown away and the original exception wins. So with B, anyone holding only an email can learn "registered but unverified" and "locked right now".
**Why C beats A.** A strands a user who never verified: they see "invalid email or password" forever, a password reset (§1.6) doesn't change that, and nothing tells them to use resend. C tells them only after they've proven the password, and someone who has the password learns nothing useful from it.
**Why "locked" must stay a pre-check with the generic answer.** If "locked" were revealed only after a correct password, an attacker guessing during the lock would get a *different* response on the right guess. The lock would become a **password oracle**. The cost: a locked real user sees the generic message, so the message itself says sign-in pauses after repeated failures.
**Why 403 and not 401** for the unverified case: the caller is identified (password proven) and refused, which is the §1.1 definition of 403.

#### D2 — Lockout policy

**Numbers (recommended):** **5** consecutive wrong passwords → locked for **15 minutes**. Both in typed config.
**What the counter does after a lock:**

| Option | After the lock expires | Cost |
|---|---|---|
| **A. Reset the counter when the lock is applied** (recommended) | 5 fresh attempts | None: the reset rides in the same `UPDATE` that applies the lock |
| B. Keep counting | The next single failure re-locks | The owner loses the account to one typo after every lock |
| C. Escalating locks (15m, 30m, 1h …) | Longer each time | New state (a lock count column), and a targeted victim is locked for hours |

**Why A.** Lockout punishes the **account owner**, not the attacker. Its job is to cap guessing speed on one account: 5 per 15 minutes is 480 a day, which is harmless against a ≥ 12-character password. Throttling the *attacker* is Phase 10's rate limiter. B and C make the owner's situation worse without stopping a denial of service: an attacker keeps a victim locked with 1 request per 15 minutes under B, or 5 under A, and both are trivial.
**Also part of this policy:** only wrong passwords count. Attempts during a lock don't (they raise a *locked* event, not a *bad credentials* one, verified), and a correct password on an unverified account doesn't. A successful login resets the counter.

#### D3 — The `security_events` shape

The SQL and a column-by-column table are in *Requirements*. The choices inside it:

| Choice | Recommended | Alternative, and what goes wrong with it |
|---|---|---|
| IP column type | **`inet`** | `varchar(45)` (the compromise): no validation without a regex `CHECK`, and the same address in two spellings doesn't match. Tomcat reports IPv6 localhost as `0:0:0:0:0:0:0:1`; a search for `::1` misses it. (Verified: `inet` stores `::1` and compares equal.) |
| Event types in the `CHECK` | **All six Phase 1 types now**, §1.6's included | Only §1.5's: §1.6 then needs a migration that drops and re-adds the `CHECK` |
| Why a login failed | **A `failure_reason` column**, present exactly when the event is `LOGIN_FAILED` | None: the history can't tell the owner "someone is guessing" (wrong password) from "you haven't verified" |
| Failed logins for unknown emails | **Not recorded** | A nullable owner: rows nobody can see, typos of non-users' emails stored as data, and one row per attempt during a credential-stuffing run. Counting them is a **metric** (Phase 12), throttling them is Phase 10. |
| When a user is deleted | **`ON DELETE CASCADE`** | `SET NULL` keeps rows whose IPs still identify the person, so an erasure request isn't actually met |
| Retention (a written stance, nothing built) | **12 months, then deleted by Phase 9's cleanup job** | Keeping forever: IP addresses are personal data under GDPR, and "as long as needed to investigate a compromised account" is the stated purpose |

#### D4 — Append-only: how the entity is mapped, and how "never updated" is enforced

| Option | What you get | The problem |
|---|---|---|
| a. Extend `BaseEntity` | `created_at/by`, `updated_at/by` | `updated_*` on a table that must never be updated; `created_at` from auditing next to `occurred_at` from the `Clock` (the **two-clocks** problem that made you drop a `CHECK` in V3); `created_by = system` on every anonymous login attempt |
| **b. Recommended: split `BaseEntity`**, and enforce in both layers | A new `@MappedSuperclass` holding only the id and its sequence; `BaseEntity` extends it; `SecurityEvent` extends it too. **`@Immutable`** on the entity, and a **database trigger that rejects `UPDATE`** | Costs one small refactor of a Phase 0 class (no column changes anywhere) and 8 lines of PL/pgSQL |
| b without the trigger (the compromise) | `@Immutable` only | Verified in Hibernate's Javadoc: changes to an `@Immutable` entity "are ignored, with no exception thrown". A bug that edits an event, or a hand-written `UPDATE`, **silently** rewrites or loses audit history. The trigger makes it loud, for every writer, including `psql`. |
| Copy the `@Id` + `@SequenceGenerator` into `SecurityEvent` | Works | Two copies of the id-generation settings that must stay identical by hand |

#### D5 — A wrong `currentPassword` in §1.6 (raised now because it touches the counter; decided now, not deferred)

**Recommended: 400 `CURRENT_PASSWORD_INCORRECT`**, with `field: currentPassword`, and it **counts toward lockout**, because §1.6 checks it through the same `AuthenticationManager` (same provider, same event, same counter).
- **Not 401**: a 401 means "you're not authenticated", and a Phase 2 client reacts by discarding its tokens and logging the user out.
- **400 over 403**: it's one wrong field in a form, so the client shows it next to the field.
- **Why it must count:** in Phase 2, a stolen access token plus an uncounted change-password endpoint is an unlimited password-guessing oracle, and the right guess means account takeover.

---

### What we're building

**Signing in, and a record of it.**

1. **`POST /api/v1/auth/login`** with `{email, password}`. A correct password on a verified, unlocked account → **200** with the account summary (the same body as registration). Phase 2 adds tokens to this response; today it only proves the credentials.
2. **Lockout.** After 5 wrong passwords in a row, the account is locked for 15 minutes, **whichever way the password was sent**: `/auth/login` or a Basic header on any request. It unlocks by itself (the §1.2 principal already treats `locked_until` in the past as unlocked). A successful login resets the count.
3. **A security log**, `security_events`: `LOGIN_SUCCEEDED`, `LOGIN_FAILED` (with the reason), `ACCOUNT_LOCKED`, and `EMAIL_VERIFIED` (the item carried from §1.4). §1.6 adds `PASSWORD_RESET` and `PASSWORD_CHANGED`. Rows are never updated.
4. **`GET /api/v1/users/me/login-history`**: your own sign-ins, failures and lockouts, newest first, paginated. There's no user id in the path.

**Out of scope, on purpose:**

| Not in §1.5 | Where it goes |
|---|---|
| Tokens in the login response; JSON 401 for Basic | Phase 2 |
| Per-IP / per-email rate limiting, CAPTCHA | Phase 10 |
| Emailing the user when their account is locked | Phase 9 (events + async email) |
| The retention cleanup job | Phase 9 (the `@Scheduled` candidate, alongside `user_tokens`) |
| Metrics for failed logins (including unknown emails) | Phase 12 (Micrometer) |
| Trusting a reverse proxy's `X-Forwarded-For` | Phase 11, when there is a proxy |
| Does a password reset clear the lock? | §1.6 decision |
| Admin unlock endpoint | Not planned; `update user_accounts set locked_until = null …` by hand |

### How we're building it, and why

```
POST /api/v1/auth/login {email, password}
  AuthController ── builds ClientInfo (socket IP, User-Agent) from the request
  └▶ LoginService.login(request, clientInfo)                     ← NOT @Transactional
       token = unauthenticated(email, password), details = WebAuthenticationDetails(ip)
       authenticationManager.authenticate(token)                  ← the SAME global manager Basic uses
         └▶ ProviderManager ─▶ DaoAuthenticationProvider (our bean)
               load user · PRE-check: locked? · password · POST-check: verified?
               publishes ONE event, synchronously, on this thread:
                 BadCredentials ─▶ AuthenticationEventsListener ─▶ LoginAttemptService.recordFailure  (REQUIRES_NEW)
                                     +1 (atomic UPDATE) · lock if ≥ 5 (conditional UPDATE, row count)
                                     · if this request applied the lock: record ACCOUNT_LOCKED (same tx)
                 Success        ─▶ AuthenticationEventsListener ─▶ LoginAttemptService.recordSuccess  (REQUIRES_NEW)
                                     reset, only if there's something to reset
       success           → record LOGIN_SUCCEEDED                → 200 account summary
       BadCredentials    → record LOGIN_FAILED(BAD_CREDENTIALS)  → 401 AUTHENTICATION_FAILED
       Locked            → record LOGIN_FAILED(ACCOUNT_LOCKED)   → 401 AUTHENTICATION_FAILED (same body)
       Disabled          → record LOGIN_FAILED(EMAIL_NOT_VERIFIED) → 403 EMAIL_NOT_VERIFIED
       anything else     → propagates (a DB outage is a 500, not "wrong password")

Any request with a Basic header
  BasicAuthenticationFilter ─▶ the same manager ─▶ the same provider ─▶ the same events ─▶ the same counter
  (no LOGIN_* rows: those are recorded by LoginService only. ACCOUNT_LOCKED is recorded on this path too.)

GET /api/v1/users/me/login-history?page&size
  id from @AuthenticationPrincipal ─▶ one indexed query, occurred_at desc, id desc
```

#### The choices

| Choice | Problem it solves / avoids |
|---|---|
| **`/auth/login` calls the global `AuthenticationManager`**, exposed as a bean from `AuthenticationConfiguration` | One credential check for both paths: the same provider, checks and events as Basic. Phase 2 extends this endpoint to issue tokens. |
| **One `DaoAuthenticationProvider` bean**, with our own pre- and post-checks (D1) | The checks are setters on the provider, so we must build it ourselves; being **the one provider bean** makes Spring use it for Basic too (verified: `InitializeAuthenticationProviderBeanManagerConfigurer`) |
| **`alwaysPerformAdditionalChecksOnUser` left at `true`** (the default) | A locked account still pays for bcrypt, so its timing matches a wrong password |
| **The counter follows authentication events** | Every path that checks a password counts: Basic now, §1.6's re-authentication later. No endpoint can forget. |
| **The counter's writes run in their own transaction** (`REQUIRES_NEW`, in `LoginAttemptService`, a different bean from the listener) | A failed authentication can't roll the count back, even if someone later makes the login flow transactional. A separate bean avoids self-invocation. |
| **`LoginService` is not transactional** | Nothing to roll back, and no outer transaction holding a connection while `REQUIRES_NEW` asks for a second one |
| **Atomic increment, then a conditional lock `UPDATE`; its row count decides who records `ACCOUNT_LOCKED`** | No lost updates under concurrent guesses, and exactly one `ACCOUNT_LOCKED` per lock (§1.4's "the row count is the answer", again) |
| **Reset on success only when there's something to reset** (`WHERE failed_login_attempts > 0 OR locked_until IS NOT NULL`) | Basic authenticates **every request**; without the condition, every API call writes a row |
| **`LOGIN_SUCCEEDED` / `LOGIN_FAILED` recorded by `LoginService` only** | Login history means sign-ins, not every Basic-authenticated request |
| **`ACCOUNT_LOCKED` recorded by the lockout code, on any path, in the lock's transaction** | A lock caused through Basic is recorded; the record exists exactly when the lock does |
| **`EMAIL_VERIFIED` recorded inside the verify transaction** | The record exists exactly when verification committed |
| **`SecurityEventRecorder` joins the caller's transaction** (`REQUIRED`) | Each caller decides: attempts are recorded in their own transaction, state changes in the change's transaction (see *Concepts*) |
| **Translate only `BadCredentialsException`, `LockedException`, `DisabledException`** | `InternalAuthenticationServiceException` (the database is down) stays a 500 |
| **IP from the socket** (`getRemoteAddr()`, which Spring's `WebAuthenticationDetails` also uses), never from a header | Nobody can write an arbitrary IP into the audit trail |
| **`inet` for the IP; `InetAddress` in the entity** | Validated, canonical, compact; subnet queries for investigations. Hibernate 6.6 maps `InetAddress` to `inet` itself (verified: `InetAddressJavaType` recommends `SqlTypes.INET`, `PostgreSQLInetJdbcType` exists). |
| **`SecurityEvent` holds a plain `Long userAccountId`**, not a `@ManyToOne` | An append-only log never navigates to the user; the listener records with only an id, no entity load |
| **History: owner from the principal, fixed sort, index-backed** | No IDOR, stable pages, no sort in memory |
| **`LoginRequest` validates presence and the 72-byte cap, never the minimum length** | A future policy change (say 12 → 15) must not lock out people with older passwords. The cap: verified that bcrypt's `matches()` **doesn't** reject over 72 bytes (only `encode()` does), so without it a 72-byte password with junk appended logs in. |

#### Alternatives we didn't take

| Alternative | Why we didn't take it | The problem it would cause later |
|---|---|---|
| **Counting failures in `LoginService`** (or the controller) | Only one path would count | **Now:** Basic is an unthrottled side door for brute force. **§1.6 / Phase 2:** change-password re-auth isn't counted, so a stolen access token becomes an unlimited guessing oracle for the current password. |
| **A wrapper around the provider that counts** | A close second, but it re-implements what `ProviderManager` already publishes for every attempt | Phase 2 rewrites the security config around JWT. If the wrapper isn't re-applied, lockout stops **silently**. The listener keeps working as long as the manager is Spring-built, which the silent-listener test proves. |
| **The documented `@Bean AuthenticationManager` = `new ProviderManager(provider)`** | It has no event publisher, and it isn't the manager Basic uses | Read in the source (confirm with deliberate failure 2): while a `UserDetailsService` bean exists, the global manager is auto-built from it, and your bean is only used where you inject it. Custom checks and counting then apply to `/auth/login` only, and even there the listener never fires. |
| **`@Async` listener** | The count would update after the response | Parallel guesses outrun it: 20 concurrent wrong passwords all pass before the counter reaches 5. Tests become timing-dependent. |
| **The counter in the login's transaction** (`@Transactional` login, `REQUIRED` write) | The failure rolls it back | **Lockout never happens and nothing errors.** It's the main trap of this section. |
| **Read the count in Java, add 1, save** | Two requests read 3 and both write 4 | A parallel attacker gets far more than 5 guesses before the lock |
| **`@Version` on `UserAccount` for the counter** | Concurrent failures collide | `OptimisticLockException` → 500s during a brute force, and retries that give up leave attempts uncounted |
| **`SELECT … FOR UPDATE`, then increment** | Correct, but it holds the row lock across a round trip | Under a burst on one account, requests queue on that lock and hold pool connections (pool size 5): one account under attack slows sign-in for everyone |
| **A scheduled job that unlocks accounts** | Nothing to do: expiry is computed from `locked_until` at load | Another moving part to monitor and test, for no behaviour |
| **Spring's default checks** (D1 B) | Enumeration without a password | Anyone can list which addresses are registered-but-unverified or locked right now |
| **All generic, even for unverified** (D1 A) | Honest users get stuck | Support tickets: "I reset my password and still can't log in" |
| **A distinct "locked" answer** (423 or `ACCOUNT_LOCKED`) | Reveals the account exists and is under attack | Shown only after a correct password, it's a **password oracle** during the lock |
| **Recording login history from the events** | Basic authenticates every request | One `LOGIN_SUCCEEDED` row **per API call**; the history is useless and the table grows with traffic |
| **Catching `AuthenticationException` broadly** | It includes `InternalAuthenticationServiceException` | During a database outage, users are told "invalid email or password", and monitoring sees 401s instead of 500s |
| **`X-Forwarded-For` for the IP** | Any client can set it | Forged IPs in the audit trail: an attacker hides, or frames someone else's address |
| **`/api/v1/users/{id}/login-history`** | A client-supplied id | IDOR: any user reads any user's IPs and sign-in times |
| **Client-chosen sort on history** | Sorts the index doesn't serve | In-memory sorts on a growing table. And the shared `PageableFactory` defaults to `createdAt`, which `SecurityEvent` doesn't have: a 500. |
| **Extending `BaseEntity`** (D4 a) | Mutable columns and a second clock | Audit rows with an `updated_at` that must never move, and `created_at` ≠ `occurred_at` |
| **`@Immutable` alone** (D4 compromise) | Silent | Edits to history are dropped or made without anyone knowing |

### What we're optimising for

1. **No oracle.** Unknown email, wrong password and locked give **byte-identical** bodies (bar `timestamp` and `correlationId`) and bcrypt-equal timing. Only the password holder learns "not verified".
2. **A counter that can't be lost or bypassed**: every path, survives rollback, atomic under concurrency.
3. **An audit trail you can believe**: recorded exactly when the thing happened, never rewritten, with an IP the client can't choose.
4. **Ownership by construction**: the endpoint has no id to tamper with.
5. **Loud failures**: an outage is a 500; an `UPDATE` on the log is an error.

We trade for these: three extra statements per failed login, one expected `WARN` at startup, a trigger, and more classes than a tutorial login.

### Concepts

💡 **Pre-checks, the password, post-checks** (verified, 6.5.11). `AbstractUserDetailsAuthenticationProvider.authenticate`: load the user (unknown → `BadCredentialsException` via `hideUserNotFoundExceptions`, after a dummy bcrypt for timing) → **pre-checks** (default: locked, disabled, expired). If a pre-check throws, the password is **still compared** (`alwaysPerformAdditionalChecksOnUser`, since 5.7.23, default `true`), its result is ignored, and the pre-check's exception is rethrown → **password** → **post-checks** (default: credentials expired). `setPreAuthenticationChecks` / `setPostAuthenticationChecks` replace each set. 🔍 Read `authenticate` and `performPreCheck`.

💡 **Authentication events** (verified). `ProviderManager` publishes **one** event per `authenticate()` call. A child manager doesn't re-publish what its parent already published (`parentResult` / `parentException`). `DefaultAuthenticationEventPublisher` maps the exception to the event: `BadCredentialsException` **and** `UsernameNotFoundException` → `AuthenticationFailureBadCredentialsEvent`; `LockedException` → `…LockedEvent`; `DisabledException` → `…DisabledEvent`. Events are published **synchronously on the request thread**, so a listener's exception becomes the request's exception. The event's `Authentication` is the *request* token: its name is **what was typed**, not normalised, and it can be an email with no account.

💡 **Where the `AuthenticationManager` comes from** (verified in source). Boot registers a `DefaultAuthenticationEventPublisher` bean (`SecurityAutoConfiguration`, `@ConditionalOnMissingBean`). `AuthenticationConfiguration` builds the **global** manager with that publisher, from exactly one `AuthenticationProvider` bean if there is one, otherwise from exactly one `UserDetailsService` bean. `HttpSecurity` gets a manager with **no providers of its own and the global one as its parent**, so Basic and anything calling `getAuthenticationManager()` share one provider. A `ProviderManager` you create with `new` starts with a `NullEventPublisher`. 📌 With a provider bean *and* a `UserDetailsService` bean, you'll see a `WARN` from `InitializeUserDetailsBeanManagerConfigurer` ("UserDetailsService beans will not be used…"). It's expected: the `UserDetailsService` is used, inside your provider. The message itself says to raise that logger to `ERROR` if the setup is intentional.

💡 **A record's transaction follows what it records.** This section's `REQUIRES_NEW` lesson (a preview of Phase 7):
- An **attempt** (a failed-login count, `LOGIN_FAILED`) must survive the failure it describes → **its own transaction**.
- A **state change** (`ACCOUNT_LOCKED` with the lock, `EMAIL_VERIFIED` with verification) must commit or roll back **with** the change → **the same transaction**.
- `REQUIRES_NEW` suspends the caller's transaction and takes a **second connection**. With an outer transaction open, every request briefly holds two; with a pool of 5, five concurrent requests can each hold one and wait forever for another. That's why the login flow itself has no transaction.

💡 **An atomic counter, and who applied the lock.** `UPDATE … SET n = n + 1` does the read-modify-write **inside Postgres**, under the row lock. A concurrent `UPDATE` on the same row waits, then re-checks its `WHERE` against the **new** row version (READ COMMITTED; from the Postgres docs, not experimented here, and the race run proves it). The second statement, "lock where `n >= 5`", returns 1 to exactly one request. It resets `n` to 0 in the same statement (D2), so the next request's lock `UPDATE` returns 0.

💡 **The same exception, two routes.** An `AuthenticationException` from `/auth/login` leaves your **controller**, so `@RestControllerAdvice` sees it (we translate before that). The same exception from `BasicAuthenticationFilter` never reaches the advice: it goes to the entry point (§1.1). Once you've seen both, you'll know exactly why Phase 2 needs an `AuthenticationEntryPoint`.

💡 **The client's IP.** `getRemoteAddr()` is the TCP peer, which the client can't choose. `X-Forwarded-For` is just a request header, and anyone can send it. Behind a reverse proxy, the TCP peer is the proxy; the fix then is `server.forward-headers-strategy: native` with the proxy trusted (Phase 11). ⚠️ Verified in Boot's `CloudPlatform`: when Boot **detects** a cloud platform (Kubernetes, Cloud Foundry, Heroku…), forwarded-header handling is switched on **by default**. Tomcat's `RemoteIpValve` then trusts forwarded headers from private-network addresses (Tomcat's documented default; not verified here). Decide it deliberately in Phase 11.

💡 **Postgres `inet`.** It validates on insert, stores a canonical form (verified: `0:0:0:0:0:0:0:1` is stored as `::1` and compares equal), takes 7 bytes for IPv4 and 19 for IPv6, and supports subnet operators (`ip_address << '10.0.0.0/8'`).

💡 **The first ownership rule in the codebase.** There's **no user id in the path**. The service takes the id **from the principal** (`@AuthenticationPrincipal TaskflowPrincipal`), never from the request. It's "never trust a client-supplied ID" from `PROJECT_CONTEXT.md` §4 made concrete, and the seed of Phase 4.

🎯 **Lockout is a denial-of-service tool.** Anyone who knows your email can lock you out. Auto-unlock limits the damage; per-IP limits (Phase 10) are the real fix, because they throttle the attacker instead of the victim. Have this answer ready: you will be asked.

### Requirements

**Endpoints**

| Method | Path | Caller | Body | Success | Errors |
|---|---|---|---|---|---|
| `POST` | `/api/v1/auth/login` | anyone (already permitted, §1.1) | `{email, password}` | **200** + `{id, email, username, displayName, emailVerified, createdAt}` | **400** `VALIDATION_FAILED` · **401** `AUTHENTICATION_FAILED` (unknown email, wrong password, locked: identical bodies) · **403** `EMAIL_NOT_VERIFIED` (correct password, unverified) |
| `GET` | `/api/v1/users/me/login-history?page=&size=` | authenticated (`/api/v1/**`) | — | **200** + `PageResponse` of `{occurredAt, eventType, failureReason, ipAddress, userAgent}`, newest first | **401** (Boot's JSON, from the filter chain) |
| `POST` | `/api/v1/auth/verify-email` | unchanged | unchanged | unchanged, plus an `EMAIL_VERIFIED` event | unchanged |

**Migration `V4__create_security_events.sql`** (as D3/D4 recommend; verified in a rolled-back transaction on the dev database, results below):

```sql
create table security_events (
    id              bigint       not null,
    user_account_id bigint       not null,
    event_type      varchar(32)  not null,
    failure_reason  varchar(32),
    occurred_at     timestamptz  not null,
    ip_address      inet,
    user_agent      varchar(512),

    constraint pk_security_events primary key (id),
    constraint fk_security_events_user_account
        foreign key (user_account_id) references user_accounts (id) on delete cascade,
    constraint ck_security_events_event_type
        check (event_type in ('LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'ACCOUNT_LOCKED',
                              'EMAIL_VERIFIED', 'PASSWORD_RESET', 'PASSWORD_CHANGED')),
    constraint ck_security_events_failure_reason
        check (failure_reason in ('BAD_CREDENTIALS', 'ACCOUNT_LOCKED', 'EMAIL_NOT_VERIFIED')),
    constraint ck_security_events_reason_iff_login_failed
        check ((event_type = 'LOGIN_FAILED') = (failure_reason is not null))
);

-- Serves the history query (newest first) and the FK: its leading column is user_account_id.
create index ix_security_events_user_account_occurred
    on security_events (user_account_id, occurred_at, id);

-- Append-only, enforced for every writer. DELETE stays allowed (cascade, retention purge).
create function security_events_reject_update() returns trigger
    language plpgsql as $$
begin
    raise exception 'security_events is append-only: UPDATE is not allowed';
end;
$$;

create trigger trg_security_events_append_only
    before update on security_events
    for each row execute function security_events_reject_update();
```

| Column / object | Type | What it's for | Why each constraint and index |
|---|---|---|---|
| `id` | `bigint` | Primary key, from `global_id_seq` through Hibernate (the new id-only superclass) | `pk_security_events` |
| `user_account_id` | `bigint not null` | Whose event it is | **FK `ON DELETE CASCADE`**: deleting a user deletes their IPs and history (D3). **`NOT NULL`**: no ownerless rows, because unknown-email attempts aren't recorded. No separate FK index: it's the leading column of `ix_…_occurred`. |
| `event_type` | `varchar(32) not null` | What happened | **`CHECK` list**: a typo or an unplanned type can't be written. All six Phase 1 types now, so §1.6 needs no migration. |
| `failure_reason` | `varchar(32)`, nullable | Why a login failed | **`CHECK` list** of the three reasons; **`ck_…_reason_iff_login_failed`**: present **exactly** when the type is `LOGIN_FAILED` |
| `occurred_at` | `timestamptz not null` | When it happened, from the injected `Clock` | The only timestamp (no auditing `created_at`: one clock). Second column of the index. |
| `ip_address` | `inet`, nullable | The TCP peer of the request | `inet` rejects anything that isn't an address, including a forwarded-for list (verified). **Null** when there was no HTTP request (a future job or admin action), never a placeholder like `127.0.0.1`. |
| `user_agent` | `varchar(512)`, nullable | The client software, for "was this me?" | The cap bounds row size against a hostile header. The application truncates first, so the cap never turns a login into a 500. |
| `ix_security_events_user_account_occurred` | index `(user_account_id, occurred_at, id)` | The history query | Verified plan: `Index Scan Backward`, **no Sort node**, for `where user_account_id = ? … order by occurred_at desc, id desc limit n`. Both columns sort the same way, so a backward scan serves `desc` and no `DESC` is needed in the index. Also makes the cascade delete an index lookup. |
| `trg_security_events_append_only` + function | row-level `BEFORE UPDATE` trigger | Makes the table append-only for **every** writer | Row-level, so an `UPDATE` that matches nothing doesn't error (verified: `UPDATE 0`). `DELETE` isn't blocked, so cascade and retention still work (verified). |

📊 **Verification run (2026-09-27, dev Postgres 17, inside `begin … rollback`, negative ids so the sequence wasn't touched):** 3 valid rows inserted · `0:0:0:0:0:0:0:1` stored as `::1`, equal to `'::1'::inet` · rejected, each by the expected constraint: a `LOGIN_FAILED` without a reason, a success with a reason, an unknown type, an unknown reason, `'not-an-ip'`, `'203.0.113.7, 10.0.0.1'`, a 513-character user agent, an unknown user · `UPDATE` → *"security_events is append-only"* · an `UPDATE` matching nothing → `UPDATE 0` · history plan as above · deleting the user cascaded 3 → 0 rows · after `rollback`, the table doesn't exist. **Nothing was left in your dev database.**

Once applied, **V4 is frozen** like V1–V3.

**`IdentifiedEntity`** (`common/persistence`, D4):
- [x] A new `@MappedSuperclass` holding only `id` and its sequence generator, moved out of `BaseEntity`.
- [x] `BaseEntity` extends it; its audit fields are unchanged.

*Done when:* the existing suite is green and no table's columns changed (`ddl-auto: validate` passes).

**`LockoutProperties`** (`user` package, a record like `TokenProperties`):
- [x] `taskflow.security.lockout.max-failed-attempts` (at least 1) and `taskflow.security.lockout.duration` (positive), validated at startup.
- [x] `application.yml` sets the D2 values (5, `15m`).

*Done when:* a missing or non-positive value stops startup with a message naming the property.

**`TaskflowSecurityConfig`:**
- [x] One `DaoAuthenticationProvider` bean, built from the `UserDetailsService` and the `PasswordEncoder`.
- [x] Its pre-checks reject only a locked account (`LockedException`).
- [x] Its post-checks reject an unverified account (`DisabledException`).
- [x] `alwaysPerformAdditionalChecksOnUser` isn't changed.
- [x] An `AuthenticationManager` bean that returns `AuthenticationConfiguration.getAuthenticationManager()`; nothing calls `new ProviderManager(…)`.

*Done when:* the startup log says *"Global AuthenticationManager configured with AuthenticationProvider bean with name …"* (plus the expected `WARN`), and never *"…configured with UserDetailsService bean…"*.

**`UserAccountRepository`**, four new queries:
- [x] `findIdByEmail(email)` → an optional id.
- [x] `incrementFailedLoginAttempts(id)`: one `UPDATE` adding 1 in SQL; returns the row count.
- [x] `lockIfThresholdReached(id, maxAttempts, lockedUntil)`: sets `locked_until` and the counter to 0, only where the counter ≥ `maxAttempts`; returns the row count.
- [x] `resetFailedLoginAttempts(id)`: counter to 0 and `locked_until` to null, only where either is set; returns the row count.

*Done when:* the JPA slice tests below pass.

**`LoginAttemptService`** (`user` package):
- [x] `recordFailure(rawEmail, ip)` runs in `REQUIRES_NEW`.
- [x] It normalises the email; an unknown email does nothing.
- [x] It increments, then tries to lock with `lockedUntil = now (Clock) + duration`.
- [x] When the lock `UPDATE` returns 1, it records `ACCOUNT_LOCKED` with the IP, in the same transaction.
- [x] `recordSuccess(accountId)` runs in `REQUIRES_NEW` and calls the conditional reset.

*Done when:* with a fixed `Clock`, the 5th failure locks until exactly now + 15m and records one `ACCOUNT_LOCKED`; the 4th doesn't.

**`AuthenticationEventsListener`** (`user` package, a separate bean):
- [x] On `AuthenticationFailureBadCredentialsEvent`: `recordFailure(name, ip from WebAuthenticationDetails or null)`.
- [x] On `AuthenticationSuccessEvent` with a `TaskflowPrincipal`: `recordSuccess(id)`.
- [x] It handles no other event (locked and disabled failures don't count).
- [x] It's synchronous: no `@Async`.

*Done when:* 5 wrong passwords lock the account through `/auth/login` **and** through a Basic header.

**Security events** (`user/event`: `SecurityEventType`, `LoginFailureReason`, `SecurityEvent`, `SecurityEventRepository`, `SecurityEventRecorder`):
- [x] Both enums match the `CHECK` lists and are stored as `STRING`.
- [x] `SecurityEvent` extends `IdentifiedEntity`, is `@Immutable`, has a protected no-arg constructor, no setters, and one factory per event type.
- [x] `userAccountId` is a plain `Long`; `ipAddress` is an `InetAddress`; `occurredAt` is an `Instant`.
- [x] The repository pages a user's events filtered by type.
- [x] `SecurityEventRecorder.record…` methods are `@Transactional` (`REQUIRED`) and take "now" from the `Clock`.
- [x] The IP string becomes an `InetAddress` in **one** place, only when it isn't null (see *Traps*).

*Done when:* an event saves and reads back with its `inet` value, and a native `UPDATE` on it throws.

**`ClientInfo`** (`common/web`, a record):
- [x] `ClientInfo.from(HttpServletRequest)` takes the IP from `getRemoteAddr()` and the `User-Agent` header.
- [x] The user agent is cut to 512 characters; a missing header stays null.

*Done when:* a 600-character user agent comes out as 512 characters.

**`LoginRequest`** (`user` package):
- [x] `email`: `@NotBlank`, `@Size(max = 254)`.
- [x] `password`: `@NotBlank`, `@MaxUtf8Bytes(72)`, **no minimum length**.
- [x] `toString()` masks the password.

**`UserErrorCode`** and an exception:
- [x] `AUTHENTICATION_FAILED` (401) and `EMAIL_NOT_VERIFIED` (403).
- [x] A `LoginFailedException` (extends `ApplicationException`) carries either code.
- [x] The 401's `detail` is one fixed sentence that mentions neither the email nor the lock (e.g. "Invalid email or password. Sign-in pauses after repeated failures.").

**`LoginService`** (`user` package, **not** `@Transactional`):
- [x] It builds an unauthenticated token from the typed email and password, with `WebAuthenticationDetails(ip, null)` as its details.
- [x] It calls the `AuthenticationManager`.
- [x] On success it records `LOGIN_SUCCEEDED` and returns the account summary.
- [x] `BadCredentialsException` → records `LOGIN_FAILED(BAD_CREDENTIALS)` if the account exists → 401.
- [x] `LockedException` → records `LOGIN_FAILED(ACCOUNT_LOCKED)` → 401, the same body.
- [x] `DisabledException` → records `LOGIN_FAILED(EMAIL_NOT_VERIFIED)` → 403.
- [x] Any other exception propagates unchanged.
- [x] It never touches `SecurityContextHolder` (stateless: nothing would keep it).

*Done when:* unknown email, wrong password and locked return bodies identical except `timestamp` and `correlationId`.

**`AuthController`:**
- [x] `POST /login` → `@Valid LoginRequest` → `LoginService` with `ClientInfo.from(request)` → 200.
- [x] `POST /verify-email` passes `ClientInfo` through; verification records `EMAIL_VERIFIED` in its own transaction.

*Done when:* a verify creates one `EMAIL_VERIFIED` row; a failed verify creates none.

**`UserController`** (`user` package, `@RequestMapping("/api/v1/users/me")`; §1.7 adds the profile here) and a small read service:
- [x] `GET /login-history` takes `page` and `size`, and the id from `@AuthenticationPrincipal`.
- [x] Types shown: `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `ACCOUNT_LOCKED`.
- [x] The sort is fixed: `occurredAt` desc, then `id` desc (pass it to `PageableFactory` explicitly).
- [x] The query runs in a `readOnly` transaction.

*Done when:* two users each see only their own rows, newest first, and repeated Basic calls add no rows.

**Written down, not built:**
- [x] The retention stance (D3) as one line in this doc's Decisions table (decision 20).

### Traps ⚠️

**The counter:**
- **The rollback trap, the main one of this section.** A `@Transactional` login with the counter written in that same transaction: authentication throws → rollback → **the counter never increments**, lockout never happens, and nothing errors. The proof is an integration test: 5 wrong passwords, then the **correct** one → still rejected.
- **The silent listener.** A `ProviderManager` you create with `new` has a **no-op publisher**; your listener never fires. Prove it fires, on **both** paths, with a test.
- **Two managers.** The documented "publish an `AuthenticationManager` bean" example builds a new `ProviderManager`. While a `UserDetailsService` bean exists, Basic keeps using an auto-built manager, so your checks and counting cover `/auth/login` only (read in source).
- **The Basic side door.** Counting in the login controller leaves Basic unthrottled. Drive the counter from events.
- **Lost updates.** Two concurrent failures read 3 and write 4. Increment in SQL. The `@Modifying` side effect: it bypasses the persistence context and JPA auditing, so `updated_at` doesn't move. That's arguably right for a counter.
- **The typed name.** The failure event carries the email **as typed**: normalise it in the listener, or `Alice@X.com` brute-forces uncounted.
- **Unknown emails fire the same event.** `findIdByEmail` returns empty; do nothing. Per-IP throttling is Phase 10.
- **Self-invocation.** An `@EventListener` calling a `@Transactional` method **in the same class** gets no transaction. Two beans.
- **`REQUIRES_NEW` under an outer transaction** takes a second connection; with a pool of 5 that can deadlock under load. Keep the login flow non-transactional.
- **A write on every Basic request.** `AuthenticationSuccessEvent` fires on each one; make the reset conditional.
- **Listener exceptions surface in the request.** The listener runs inside `authenticate()`: if the database is down, the login is a 500. That's correct (fail closed), not something to catch.

**What the response reveals:**
- **Undoing Spring's protection.** A pre-lookup ("does this email exist?") before authenticating reintroduces the enumeration and timing difference Spring removed. Look up the id only **after** a failure, only to record it.
- **Showing "locked" only after a correct password** turns the lock into a password oracle.
- **Catching `AuthenticationException` broadly** turns a database outage into "invalid email or password".
- **A minimum length on the login password** locks out existing users the day the policy tightens.
- **No byte cap on the login password.** `matches()` compares only the first 72 bytes (verified: the 72-byte check applies to `encode()` only).

**The security log:**
- **`X-Forwarded-For` is client-controlled.** Read `getRemoteAddr()`. And know that Boot enables forwarded headers by itself on a detected cloud platform (verified).
- **`InetAddress.getByName(null)` returns the loopback address** (JDK Javadoc): a missing IP would be recorded as `127.0.0.1`, a lie in the audit trail. Keep null as null. Only ever pass it the literal from `getRemoteAddr()`: given a host *name*, it does a DNS lookup on the request thread.
- **An untruncated user agent** hits the `varchar(512)` cap: the insert fails, and the login becomes a 500.
- **`@Immutable` is silent** (verified): changes are ignored with no exception. The trigger is what makes a mistake visible.
- **`PageableFactory` defaults to sorting by `createdAt`**, which `SecurityEvent` doesn't have: pass the sort explicitly.
- **A new event type later needs a migration** that drops and re-adds the `CHECK`. On a large table, add it `NOT VALID` and `VALIDATE` separately, to avoid a long lock.
- **IP addresses are personal data.** The retention line (D3) is the minimum. Don't log them at INFO either.

**Ownership:**
- **A user id in the path is an IDOR.** Take it from `@AuthenticationPrincipal`.

### Deliberate failures

| # | Create this | What you'll see | Lesson |
|---|---|---|---|
| 1 | **Spring's default checks** (before moving "verified" to the post-checks): log in as an unverified user (`bob`) with a **wrong** password | **403 `EMAIL_NOT_VERIFIED` for a wrong password**: the account's state leaks to anyone who knows only the email | Pre-checks run before the password decides anything. Then move the check and repeat: the generic 401. |
| 2 | **The documented manager bean**: `@Bean AuthenticationManager` = `new ProviderManager(yourProvider)`, and no provider bean | Startup log: *"Global AuthenticationManager configured with UserDetailsService bean…"* (Basic isn't using your provider). Wrong passwords via `/auth/login` never increment the counter. | Two managers, and the second one has a no-op publisher (both old notes' "silent listener" and the two-managers trap) |
| 3 | **The rollback trap**: make `LoginService` `@Transactional` and the counter write `REQUIRED` | 5 wrong passwords → counter still 0 in `psql`; the correct password → 200 | A failure rolls back everything written in its transaction. Then restore `REQUIRES_NEW` **with** the transactional login: counter correct again. Then remove the transaction from the login too. |
| 4 | **Lost updates**: count in Java (load, +1, save). Set `max-failed-attempts: 1000` and fire 20 concurrent wrong passwords (command below) | `failed_login_attempts` < 20 | Read-modify-write in Java loses updates. With the SQL increment: exactly 20. |
| 5 | **"Locked" revealed after a correct password**: move the lock check to the post-checks, with its own message | During a lock, the correct password gets a different answer than a wrong one | A lock that reveals the right guess is a password oracle |
| 6 | **History from the events**: record `LOGIN_SUCCEEDED` in the success listener; call `GET /api/v1/organizations` 5 times with Basic | 5 new "logins" | Basic authenticates every request; history belongs to the login endpoint |
| 7 | **Trusting `X-Forwarded-For`**: set `server.forward-headers-strategy: native` in dev, then log in with `-H 'X-Forwarded-For: 203.0.113.99'` | Expected: `203.0.113.99` recorded as the IP (localhost counts as a trusted proxy by Tomcat's default; not yet verified) | Forwarded headers are only as trustworthy as the proxy in front. Remove the setting. |
| 8 | **`@Immutable` only**: in `psql`, `update security_events set ip_address = '1.2.3.4' where id = …` | With the trigger: an error. (Without it, the history is rewritten silently.) | Enforce append-only where every writer passes |
| 9 | **Mutation checks** once the tests exist | Each planted bug turns a test red | See *Tests* |

**The race command** (#4 and the 📊 race), 20 concurrent wrong passwords for one account:

```bash
seq 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -d '{"email":"carol@example.com","password":"wrong-password-{}"}' localhost:8080/api/v1/auth/login | sort | uniq -c
```

```sql
select failed_login_attempts, locked_until from user_accounts where username = 'carol';
select event_type, failure_reason, count(*) from security_events
where user_account_id = (select id from user_accounts where username = 'carol') group by 1, 2;
update user_accounts set failed_login_attempts = 0, locked_until = null where username = 'carol';   -- reset afterwards
```

### Build order

1. ~~**Settle D1–D5.**~~ Done 2026-09-27: all recommendations accepted (decisions 17–22).
2. **`IdentifiedEntity` split** → suite green. **V4 migration** → app starts; `\d security_events` in `psql`; try an `UPDATE` (deliberate failure 8).
3. **`LockoutProperties`** + config values.
4. **`LoginRequest`, the error codes, `LoginService` (authenticate and translate only, no recording yet), `POST /login`, and the `AuthenticationManager` bean.** Run it: 200 / 401 / 403. ⚠️ **Deliberate failure 1 first**, with Spring's default provider, then build the provider bean with your checks and repeat. Check the startup log lines (deliberate failure 2 shows the wrong ones).
5. **The four repository queries, `LoginAttemptService`, the listener.** ⚠️ Deliberate failures **3** (rollback) and **4** (lost updates, with the race command), then **5**.
6. **`SecurityEvent` and friends, `ClientInfo`; record `LOGIN_*` in `LoginService`, `ACCOUNT_LOCKED` in `LoginAttemptService`, `EMAIL_VERIFIED` in verify.** ⚠️ Deliberate failures **6** and **7**.
7. **`GET /users/me/login-history`.**
8. **Run it by hand:**
   - 5 wrong passwords via `/auth/login` → the 5th is still 401; `psql` shows `locked_until` ≈ now + 15m, counter 0, one `ACCOUNT_LOCKED`.
   - The correct password → 401, **byte-identical** to a wrong one and to an unknown email (`diff` the bodies after removing `timestamp` and `correlationId`).
   - Unlock by `update … set locked_until = now()` → login 200, counter reset.
   - The same 5 failures through Basic (`curl -u carol@example.com:wrong …/organizations`) → locked, `ACCOUNT_LOCKED` recorded, **no** `LOGIN_*` rows.
   - `bob` (unverified): wrong password → 401; correct password → 403 `EMAIL_NOT_VERIFIED`.
   - Login history for `carol` → newest first; for another user → none of carol's rows.
   - Register → verify → one `EMAIL_VERIFIED` row.
   - The race command.
9. **Tests**, then mutation checks, then 📊 measure.

### Tests

**New test infrastructure:** an **adjustable `Clock`** (`@Primary`, in the shared `TestcontainersConfiguration`, reset per test like `CapturingEmailSender`), so a test can move 15 minutes ahead without sleeping, and without starting a new context.

| Kind | Must prove |
|---|---|
| **Unit** | The provider with your checks (a fake `UserDetailsService`, the real encoder): locked + **correct** password → `LockedException`; unverified + wrong password → `BadCredentialsException` (**not** `DisabledException`); unverified + correct → `DisabledException` · `LoginAttemptService` (Mockito, fixed `Clock`): unknown email → no `UPDATE`; increment before lock; lock row count 1 → `ACCOUNT_LOCKED` recorded with `now + duration`; 0 → nothing recorded; email normalised · `LoginService`: each of the three exceptions → its code and its `LOGIN_FAILED` reason; `InternalAuthenticationServiceException` → propagates unchanged, nothing recorded; success → `LOGIN_SUCCEEDED` · `ClientInfo`: truncation to 512, missing user agent → null · `LoginRequest.toString()` has no password |
| **JPA slice** | Increment: 1 row, +1 · lock: 0 rows below the threshold, 1 at it, and the counter becomes 0 · reset: 0 rows when already clean · `SecurityEvent` round-trips an IPv6 address · the reason `CHECK` · a native `UPDATE` → exception (trigger) · history paging: newest first, a tie on `occurred_at` broken by `id` desc · cascade on user delete |
| **Web slice** (`AuthController` + security config) | Blank fields → 400; 73-byte password → 400; the password never appears in a 400 body · 401 / 403 bodies carry the right `code` |
| **Integration** (real HTTP, adjustable clock) | **5 wrong, then correct → still 401** (the rollback trap) · the same through **Basic** (the side door), which also proves the listener fires on both paths · clock past `locked_until` → 200 and the counter reset · unknown email, wrong password and locked: bodies identical except `timestamp` / `correlationId` · unverified: wrong → 401, correct → 403 · 3 wrong, 1 right, 4 wrong → **not** locked · history: own rows only, newest first; 5 Basic `GET`s add **no** rows; `ACCOUNT_LOCKED` present after a Basic lockout; a forged `X-Forwarded-For` isn't the recorded IP · verify → one `EMAIL_VERIFIED`; failed verify → none |
| 📊 **Race** | 20 concurrent wrong passwords with a high threshold → counter **exactly 20**; with the threshold at 5 → **exactly one** `ACCOUNT_LOCKED`, zero 500s |

**Mutation checks** (each must turn a test red): verified check back in the pre-checks · lock check moved to the post-checks · counter written with `REQUIRED` inside a `@Transactional` login (and note: removing `REQUIRES_NEW` alone turns nothing red while the login has no transaction; that's defence in depth, so plant both) · increment in Java · listener without normalisation · `new ProviderManager` · history recorded from the success event · `X-Forwarded-For` trusted · broad `catch (AuthenticationException)` · reset without its condition (the JPA test on row count).

📊 **Measure:**
- **Timing:** 50 logins with a wrong password for a known email vs 50 for an unknown one; compare medians. Expect them close (both pay one bcrypt); the known email also does the counter `UPDATE`s and an insert. Record the gap: that's the honest answer to "is there a timing leak?".
- **Suite:** time and container count before and after; the adjustable clock must not create a new context.

🎯 **Interview questions:**
- "How does your login avoid user enumeration, including timing? Where does it still leak?"
- "Your lockout counter never incremented. Why?" (The rollback trap.)
- "How does lockout work under concurrent requests?" (The SQL increment, the row lock, the conditional lock and its row count.)
- "Isn't lockout a denial-of-service vector? What would you add?"
- "Why doesn't a locked account get a 'locked' message?"
- "Why is the lockout counter driven by events, but login history recorded by the endpoint?"
- "Where does your `AuthenticationManager` come from, and how did you make sure Basic and `/login` share it?"
- "When do you use `REQUIRES_NEW`, and what does it cost?"
- "How do you get the client's real IP? Why not `X-Forwarded-For`?"
- "How do you make an audit table append-only?"

---

## 1.6 — Password reset & password change ✅ built (`63bb1b4`, `4e115a2`; **tests deferred**, see the learning log)

> **Time-box, honestly:** Phase 1 was budgeted at 7h (hard stop 10.5h), and §1.1–§1.5 have almost certainly used that. `PROJECT_CONTEXT.md` §3.7 says: ship what works. So each decision below also names its **cheaper option**, and the phase's trim order still applies: §1.7 (profile) goes first.
> **No schema change in §1.6.** `user_tokens` already accepts `PASSWORD_RESET`, `security_events` already accepts `PASSWORD_RESET` / `PASSWORD_CHANGED`, and `user_accounts` has `password_changed_at`. D5 is a mapping change, not a migration.
> **Rewritten from the old notes (2026-09-25).** Everything in them is kept below; decision 22 (a wrong `currentPassword`) was settled in §1.5.

### 🏗️ Decisions ✅ all five recommendations accepted 2026-09-29 (Decisions table rows 23–27)

**Verified before writing these** (Hibernate 6.6.53, on a scratch copy of the project with a Testcontainers database, 2026-09-29):

| In one transaction | Result |
|---|---|
| Clear the lock with `resetFailedLoginAttempts` (bulk `UPDATE`), then change the password through the loaded entity | Hibernate's entity `UPDATE` sets **every column**: `failed_login_attempts = 5`, `locked_until` back. The unlock was undone, with no error. |
| The same, with `@Modifying(clearAutomatically = true)` | The unlock stayed, but clearing detached the loaded entity: **the new password was never written**. No error. |
| `@DynamicUpdate` on `UserAccount` | The entity `UPDATE` listed only `password_changed_at`, `password_hash`, `updated_at`. Both changes survived. |

#### D1 — Does a successful reset clear the lockout?

**Recommended: yes**, and it resets the counter too.
- **Why:** the lock exists to stop someone **guessing** the password. A reset proves control of the mailbox, which is stronger evidence than any guess, and the old password stops mattering the moment it's replaced.
- **The alternative (no) and its failure:** a user who locked themselves out by forgetting the password resets it, then gets "invalid email or password" for up to 15 minutes **with the correct new password**. They assume the reset failed and reset again.
- **Cost:** none. It's two fields in the same entity write.

#### D2 — Does a successful reset verify an unverified email?

**Recommended: yes.** It also revokes the account's active verification link and records `EMAIL_VERIFIED`.
- **Why:** clicking a link sent to the address is exactly the proof verification asks for.
- **The alternative (no) and its failure:** an unverified user who forgot their password resets it and gets **403 `EMAIL_NOT_VERIFIED`**. Now they need a second email and a second click, for something they've already proven.
- **The revoke** keeps the "one live link" rule honest: an old verification link that still worked would do nothing harmful, but nothing useful either.
- **Cheaper option:** verify, but skip the revoke. The old link then only re-stamps `email_verified_at`, which is the known §1.4 nit.

#### D3 — The reset token's lifetime

**Recommended: keep the configured `30m`.**

| Option | The problem |
|---|---|
| 15 minutes | Delayed corporate mail and greylisting can take longer; the user gets an expired link and requests again |
| **30 minutes** | Room for slow mail; a leaked link is only useful for half an hour |
| 1 hour | Double the exposure of a forwarded or screenshotted link, for little gain |
| 24 hours (like verification) | A reset link **is** account takeover. A day-old link in a shared inbox, a backup or a mail log is a live credential. |

Verification can live 24h because its worst case is "someone verified an address they didn't sign up with"; a reset link's worst case is losing the account.

#### D4 — Notify the owner by email after a reset or a change?

**Recommended: yes.** After commit, send the stored address "Your password was changed. If this wasn't you, reset it now." A send failure is logged by account id and doesn't fail the request.
- **Why:** it's the only way the real owner learns about a takeover while they can still act. Most takeovers are discovered this way, and OWASP's forgot-password guidance recommends it.
- **The alternative (no) and its failure:** an attacker who got into the mailbox or a live session changes the password, and the owner finds out days later, when they're already locked out.
- **Cheaper option:** defer it to Phase 9, which builds notifications anyway. It's one more email template and one send-after-commit.

#### D5 — Protect `user_accounts` from whole-row writes

The lockout counter is written by bulk SQL (§1.5), and everything else through the entity. The verified table above shows what happens when they meet.

| Option | What it does | The problem |
|---|---|---|
| **a. Recommended: `@DynamicUpdate` on `UserAccount`, and every §1.6 change through entity methods** | Entity writes touch only the columns that changed | Hibernate builds the `UPDATE` per flush instead of reusing one statement: negligible at this scale |
| b. Only the rule "never mix bulk and entity writes in one transaction" | Avoids the verified failures if everyone remembers | A change-password transaction that loads the account, then commits after a concurrent wrong-password guess applied a lock, writes back the **old** counter and `locked_until = null`: **the lock is erased**. The same is true today for `verify`. The window is milliseconds, but it's a silent unlock during exactly the attack lockout exists for. |
| c. `@Version` on `UserAccount` | Detects the conflict | The bulk counter queries don't bump the version, so it doesn't see them; and every real conflict becomes a 409 or 500 on a password change |
| d. `clearAutomatically = true` | — | Verified: the entity change is silently lost |

---

### What we're building

**Getting back in when you've forgotten your password, and changing it when you know it.**

1. **Forgot it:** `POST /api/v1/auth/password-reset/request` with `{email}` → **always 202**, whatever the email. If the account exists, its owner gets a link: `{frontend-base-url}/reset-password#token=…`.
2. **Set a new one:** `POST /api/v1/auth/password-reset/confirm` with `{token, newPassword}` → **204**. The link works once, for 30 minutes. It also unlocks the account (D1) and verifies the email (D2). Then the owner is notified (D4).
3. **Change it while signed in:** `PUT /api/v1/users/me/password` with `{currentPassword, newPassword}` → **204**. The current password must be correct (checked like a login, so it counts toward lockout, decision 22). The new one must differ. Any outstanding reset link stops working. Then the owner is notified (D4).
4. **Every change is recorded:** `PASSWORD_RESET` or `PASSWORD_CHANGED` (and `EMAIL_VERIFIED` when a reset verifies), in the same transaction as the change.

**Out of scope, on purpose:**

| Not in §1.6 | Where it goes |
|---|---|
| Signing out other devices after a change | Phase 2: tokens issued before `password_changed_at` are rejected. That's why the column exists now. |
| Logging the user in automatically after a reset | Not planned (see *Alternatives*) |
| Rate limiting reset requests (email bombing) | Phase 10 |
| Removing the reset request's timing leak | Phase 9 (async sending) |
| Password history ("not one of your last 5") | Not planned: NIST 800-63B doesn't ask for it |
| Checking new passwords against breach lists | Interview knowledge only (HIBP k-anonymity) |
| Email change | Deferred backlog, `PROJECT_CONTEXT.md` §6 (design notes there) |

### How we're building it, and why

```
REQUEST   POST /auth/password-reset/request {email}            → always 202
  AuthController ─▶ PasswordWorkflow.requestReset(email)           ← NOT transactional
                      ├─ PasswordService.requestReset(email)  @Transactional
                      │     account by normalised email? (unknown → nothing)
                      │     UserTokenService.issue(account, PASSWORD_RESET)   revokes the previous link (decision 12)
                      │     returns Optional<ResetToSend(accountId, storedEmail, token)>  ── COMMIT ──
                      ├─ a concurrent request hit uk_user_tokens_active → the other one sends; return (as §1.4 resend)
                      └─ AccountEmails.sendPasswordReset(...)   after commit; a failure is logged, never propagated

CONFIRM   POST /auth/password-reset/confirm {token, newPassword}   (newPassword validated at the boundary first)
  AuthController ─▶ PasswordWorkflow.confirmReset(...)
                      ├─ PasswordService.confirmReset(token, newPassword, clientInfo)  @Transactional
                      │     UserTokenService.consume(token, PASSWORD_RESET)   one conditional UPDATE: one winner
                      │     hash newPassword (only now: a bad token never costs a bcrypt)
                      │     account.resetPassword(hash, now)   hash, password_changed_at, counter 0, lock cleared (D1),
                      │                                         email verified if it wasn't (D2) → returns whether it verified
                      │     if it verified: revoke the active EMAIL_VERIFICATION token, record EMAIL_VERIFIED
                      │     record PASSWORD_RESET                                                ── COMMIT ──
                      └─ AccountEmails.sendPasswordChanged(...)   (D4)                      → 204

CHANGE    PUT /users/me/password {currentPassword, newPassword}    (authenticated)
  UserController ─▶ PasswordWorkflow.changePassword(principal, request, clientInfo)
                      ├─ newPassword equals currentPassword?  → 400 PASSWORD_UNCHANGED   (plain string compare, no bcrypt)
                      ├─ AuthenticationManager.authenticate(principal's email, currentPassword)   ← the §1.5 manager:
                      │     wrong or locked → 400 CURRENT_PASSWORD_INCORRECT                       counts toward lockout
                      ├─ PasswordService.applyChange(accountId, newPassword, clientInfo)  @Transactional
                      │     account.changePassword(hash, now) · revoke the active PASSWORD_RESET token
                      │     record PASSWORD_CHANGED                                              ── COMMIT ──
                      └─ AccountEmails.sendPasswordChanged(...)   (D4)                      → 204
```

#### The choices

| Choice | Problem it solves / avoids |
|---|---|
| **Reuse §1.4's token machinery with purpose `PASSWORD_RESET`** | Unguessable, hashed at rest, expiring, single-use, purpose-bound, one live link per user (decision 12). A verification token can never reset a password: `consume` filters on the purpose. |
| **Always 202 on request**, sending only to the **stored** address | No enumeration (unlike registration, decision 7); a request can never send a link to an address the attacker typed |
| **Link in the URL fragment** (decision 11 again) | The token never reaches a server: not in access logs, proxies or `Referer` |
| **`PasswordWorkflow` (not transactional) + `PasswordService` (transactional), two beans** | Emails leave only after commit (no phantom resets); re-authentication runs outside any transaction (§1.5's rule); no self-invocation |
| **Validate `newPassword` at the boundary, before consuming** | A too-short password is a 400 that leaves the link usable. Validated after consuming, the user's only link is burnt by a typo. |
| **Consume first, hash second** | An invalid or replayed token is rejected by one cheap `UPDATE`, never paying ~100 ms of bcrypt |
| **All account changes in one entity method per flow** (`resetPassword`, `changePassword`) plus **`@DynamicUpdate`** (D5) | No bulk-plus-entity mix in one transaction (verified failures), and no whole-row write erasing a concurrent lock |
| **Events in the same transaction as the change** (decision 36's rule) | `PASSWORD_RESET` / `PASSWORD_CHANGED` exist exactly when the change committed |
| **"Other outstanding reset tokens": nothing extra at confirm** | Decision 12's partial unique index already guarantees at most **one** active reset token, and confirm consumes it. The old notes' "invalidate every other token" is enforced by the database. |
| **Change revokes the active reset token** | A reset link requested earlier (by the owner, or by someone with mailbox access) can't undo a deliberate change |
| **Re-authentication through the §1.5 `AuthenticationManager`**, with the **principal's** email | The same checks, events and counter as a login (decision 22). Guessing the current password through this endpoint is as limited as guessing at login. |
| **`PASSWORD_UNCHANGED` by comparing the two submitted strings** | The current password is about to be proven anyway; comparing it to the new one needs no second bcrypt |
| **Wrong or locked current password → 400 `CURRENT_PASSWORD_INCORRECT`** (decision 22), and no `LOGIN_FAILED` event | A 401 would log a Phase 2 client out. It's not a login, so it doesn't belong in login history, but it still counts. |
| **One password policy annotation** (`@ValidPassword`: not blank, ≥ 12 characters, ≤ 72 UTF-8 bytes), used by register, reset and change | The rule lives in one place; reset and change can't drift from registration |
| **`AccountEmails`, one component for all account emails** (verification, reset, password changed) | One place that builds links from `FrontendProperties`, sends, and logs failures by account id only. §1.4's private send method moves here. |

#### Alternatives we didn't take

| Alternative | Why we didn't take it | The problem it would cause later |
|---|---|---|
| **404 or 409 for an unknown email on request** | Enumeration | A free "is this email registered?" oracle, and unlike registration (decision 7) there's no usability excuse |
| **Sending the link to the email as typed** | The typed form may differ from the stored one | `Alice@x.com` vs `alice@x.com` is harmless, but any future "request by username" variant could send a takeover link to an attacker-chosen address |
| **The token in a query string, or `GET /confirm?token=`** | Scanners pre-open links; servers log URLs | Corporate mail scanners **consume** the link before the user clicks, or the token sits in proxy logs as a working takeover credential for 30 minutes |
| **Sending inside the transaction** | Phantom emails | A reset email for a token that rolled back: the link never works, and the user blames the product |
| **One transactional class for the whole flow** | Re-authentication inside a transaction; sending inside it | The §1.5 connection problem (an outer transaction plus the counter's `REQUIRES_NEW` = two connections per request), and phantom emails |
| **Validating the new password after consuming** | The token is spent before the check | The user's only link is burnt by a typo; they must request another and wait for mail |
| **Hashing before consuming** | Every garbage token costs a bcrypt | ~100 ms of CPU per request on an anonymous endpoint: a cheap CPU-exhaustion attack |
| **Clearing the lock with the §1.5 bulk query in the reset transaction** | It meets the entity write | Verified: the lock comes back (whole-row write), or with `clearAutomatically` the password change is lost |
| **Logging the user in after a reset** (returning tokens in Phase 2) | Whoever holds the link gets a session without ever knowing the password | A leaked or forwarded link becomes a **session**, not just a password change. The owner's notification (D4) arrives after the attacker is already in. |
| **Rejecting a reset to the same password as before** | It needs a bcrypt `matches` against the old hash | ~100 ms more per confirm for a rule NIST doesn't ask for; and it tells a link-holder what the old password was **not** |
| **Re-authenticating with an email from the request body** | The caller is already identified | Confusing at best; at worst, a later refactor applies the change to the principal's account after checking someone else's password |
| **`passwordEncoder.matches(currentPassword, hash)` instead of the manager** | No event, no counter | The change endpoint becomes an **uncounted** oracle for the current password: with a stolen Phase 2 token, unlimited guesses, then takeover |
| **401 for a wrong current password** | Decision 22 | A Phase 2 client discards its tokens and logs the user out over a typo |
| **Recording `LOGIN_FAILED` for a wrong current password** | It isn't a login | Login history shows "failed sign-in" for a user who was signed in the whole time |
| **Leaving outstanding reset links alive after a change** | — | Someone who requested a link before the owner changed the password can still use it for up to 30 minutes and take the account back |
| **The password rule copied into each request record** | Three copies | The day the minimum becomes 15, one copy is missed: reset accepts what registration refuses |
| **A fourth private "send and log" method** | Duplication | Link building and the "never log the email or token" rule drift between three workflows |

### What we're optimising for

1. **A reset link that's useless to anyone but the owner, for as short as practical**: hashed at rest, in the fragment, single-use, 30 minutes, one live at a time.
2. **No silent partial writes**: the account change, the unlock, the verification and the event commit together, or not at all. No write erases another.
3. **No new oracles**: request is always 202; the change endpoint is as limited as login.
4. **The owner finds out** (D4).
5. **The link survives user mistakes**: a bad new password doesn't burn it.

### Concepts

💡 **Hibernate writes the whole row** (verified above). Without `@DynamicUpdate`, the `UPDATE` for a dirty entity is prepared once per entity and lists **every** mapped column, filled from the entity's in-memory state. So any column another statement changed since the entity was loaded is **overwritten with the stale value**. `@DynamicUpdate` makes Hibernate list only the columns whose values changed. This matters here because `user_accounts` is written two ways: bulk SQL for the counter (§1.5), entities for everything else.

💡 **`clearAutomatically` detaches, silently** (verified). After a bulk query, `clearAutomatically = true` empties the persistence context so later reads see fresh data. Any entity you'd already loaded becomes **detached**: changes to it are never flushed, and nothing warns you. The two flags are for "bulk, then *read*", not "bulk, then *write through an entity*".

💡 **Why the reset link can't live as long as the verification link.** A token's lifetime should match the damage it can do. Verification's worst case is a wrongly verified address; reset's is account takeover. Mail delay is the only argument for longer, and 30 minutes covers it.

💡 **Re-authentication.** Being authenticated proves you *had* the credentials when the session started. Sensitive changes ask again (OWASP), because sessions get stolen, and in Phase 2 a stolen access token would otherwise be enough to take the account over permanently. Checking it through the `AuthenticationManager` means the lockout counter covers this endpoint too.

💡 **Send after commit, again.** The shape is §1.4's `RegistrationWorkflow`: a non-transactional bean calls a transactional one on **another** bean, then sends. It's the third flow with this shape, which is why Phase 9's `@TransactionalEventListener(AFTER_COMMIT)` will be welcome.

💡 **Constraint composition** (Bean Validation). An annotation annotated with other constraints, and `@Constraint(validatedBy = {})`, is a constraint made of those constraints: `@ValidPassword` = `@NotBlank` + `@Size(min = 12)` + `@MaxUtf8Bytes(72)`. Each part still reports its own message unless you add `@ReportAsSingleViolation`.

🎯 **"After a password change, what happens to the user's other logged-in devices?"** Today: nothing, and you know that. Phase 2: every token issued before `password_changed_at` is rejected. That's why the column exists now.

### Requirements

**Endpoints**

| Method | Path | Caller | Body | Success | Errors |
|---|---|---|---|---|---|
| `POST` | `/api/v1/auth/password-reset/request` | anyone (already permitted, §1.1) | `{email}` | **202**, empty, for **every** email | **400** `VALIDATION_FAILED` (blank or malformed email) |
| `POST` | `/api/v1/auth/password-reset/confirm` | anyone (already permitted) | `{token, newPassword}` | **204** | **400** `VALIDATION_FAILED` (policy, before the token is touched) · **400** `INVALID_TOKEN` (unknown, used, expired, revoked, wrong purpose: one answer) |
| `PUT` | `/api/v1/users/me/password` | authenticated (`/api/v1/**`) | `{currentPassword, newPassword}` | **204** | **400** `VALIDATION_FAILED` · **400** `PASSWORD_UNCHANGED` (`field: newPassword`) · **400** `CURRENT_PASSWORD_INCORRECT` (`field: currentPassword`) · **401** (Basic missing or wrong) |

**Schema:** none (see the note at the top). Mapping change: `@DynamicUpdate` on `UserAccount` (D5).

**`@ValidPassword`** (`common/validator`, a composed constraint):
- [x] It combines `@NotBlank`, `@Size(min = 12)` and `@MaxUtf8Bytes(72)`.
- [x] `RegisterRequest.password` uses it instead of its three annotations.

*Done when:* registration still rejects an 11-character and a 73-byte password with the same messages as before.

**Request records** (`user` package), each with a masked `toString()`:
- [x] `PasswordResetRequest(email)`: `@NotBlank @Email @Size(max = 254)`.
- [x] `PasswordResetConfirmRequest(token, newPassword)`: `token` `@NotBlank`; `newPassword` `@ValidPassword`.
- [x] `ChangePasswordRequest(currentPassword, newPassword)`: `currentPassword` `@NotBlank @MaxUtf8Bytes(72)` (no minimum, like login); `newPassword` `@ValidPassword`.

*Done when:* none of them prints a password, a token or an email.

**`UserErrorCode`:**
- [x] `PASSWORD_UNCHANGED` (400) and `CURRENT_PASSWORD_INCORRECT` (400), thrown with a `field` property.

**`UserAccount`:**
- [x] `@DynamicUpdate` (D5).
- [x] `resetPassword(newHash, now)`: sets the hash and `passwordChangedAt`, zeroes the counter, clears `lockedUntil` (D1), sets `emailVerifiedAt` if it's null (D2), and returns whether it verified.
- [x] `changePassword(newHash, now)` stays as it is.

*Done when:* a reset of a locked, unverified account leaves it unlocked, verified, with a new `password_changed_at`, in one `UPDATE`.

**`AccountEmails`** (`user` package, a `@Component`):
- [x] `sendVerification`, `sendPasswordReset` and `sendPasswordChanged`, each taking the account id, the stored email, and the token where there is one.
- [x] Links are built from `FrontendProperties`: `/verify-email#token=…` and `/reset-password#token=…`.
- [x] A send failure is logged at ERROR with the account id only, and never propagates.
- [x] `RegistrationWorkflow` uses it instead of its private method.

*Done when:* registration and resend still send exactly as before.

**`PasswordService`** (`user` package, every public method `@Transactional`):
- [x] `requestReset(rawEmail)`: normalises; unknown → empty; otherwise issues a `PASSWORD_RESET` token and returns what to send.
- [x] `confirmReset(rawToken, newPassword, clientInfo)`: consumes the token, **then** hashes, then `resetPassword`, then (if it verified) revokes the active verification token and records `EMAIL_VERIFIED`, then records `PASSWORD_RESET`.
- [x] `applyChange(accountId, newPassword, clientInfo)`: hashes, `changePassword`, revokes the active reset token, records `PASSWORD_CHANGED`.
- [x] Every `user_accounts` change goes through the entity; no bulk query touches `user_accounts` here.

*Done when:* 20 concurrent confirms with one token give exactly one 204.

**`PasswordWorkflow`** (`user` package, **not** transactional):
- [x] `requestReset(email)`: calls the service, catches only the `uk_user_tokens_active` race (as §1.4's resend does), then sends after commit.
- [x] `confirmReset(...)`: calls the service, then sends the "password changed" email (D4).
- [x] `changePassword(principal, request, clientInfo)`: `PASSWORD_UNCHANGED` check → re-authenticate with the principal's email through the `AuthenticationManager` (details carry the IP) → `BadCredentialsException` / `LockedException` → 400 `CURRENT_PASSWORD_INCORRECT`, anything else propagates → `applyChange` → send (D4).

*Done when:* a wrong current password gives 400 **and** increments `failed_login_attempts`.

**Controllers:**
- [x] `AuthController`: `POST /password-reset/request` → 202; `POST /password-reset/confirm` → 204, with `ClientInfo.from(request)`.
- [x] `UserController`: `PUT /password` → 204, with the principal and `ClientInfo.from(request)`.

### Traps ⚠️

**Writes and transactions:**
- **Bulk plus entity in one transaction** (verified): the entity's whole-row `UPDATE` undoes the bulk change.
- **`clearAutomatically` to "fix" that** (verified): the loaded entity is detached and its changes vanish.
- **A whole-row write erasing a concurrent lock** (D5 b): the account loaded before a lock, written after it.
- **Self-invocation**: the workflow and the transactional service in one class means no transaction at all.
- **Sending inside the transaction**: a phantom reset email when the commit fails.
- **Catching the `uk_user_tokens_active` race inside the transaction**: `UnexpectedRollbackException` (§1.4). Catch it in the workflow.

**The reset link:**
- **Validating the new password after consuming** burns the user's only link on a typo.
- **Hashing before consuming** lets garbage tokens cost a bcrypt each.
- **A longer TTL "for convenience"**: a reset link is a takeover credential.
- **The link in a query string**, or logged anywhere but the dev sender.
- **Sending to the typed email**, not the stored one.
- **A different error for used vs expired vs unknown**: one `INVALID_TOKEN`.

**Enumeration and timing:**
- **Request must be 202 for everything**: unknown, unverified, locked.
- **Timing still leaks**: a known email writes a token and sends mail; an unknown one returns at once. **Note it; don't fix it.** Phase 9's async sending removes most of the gap. Have the answer ready.

**Change password:**
- **Checking `currentPassword` with `passwordEncoder.matches`**: an uncounted guessing oracle.
- **Re-authenticating with an email from the body** instead of the principal.
- **Not 401** for a wrong current password (decision 22).
- **Leaving the outstanding reset link alive** after a change.
- **Records print their secrets**: two passwords in `ChangePasswordRequest`, a token and a password in the confirm request.

**Also:**
- **`markEmailVerified` overwrites** (§1.4 nit): `resetPassword` must set `emailVerifiedAt` only when it's null.
- **The notification must not contain the new password or a link with a token.** "If this wasn't you, reset it" means pointing at the request page.

### Deliberate failures

| # | Create this | What you'll see | Lesson |
|---|---|---|---|
| 1 | **Unlock with the bulk query** in the reset transaction (before adding `@DynamicUpdate`): lock carol, reset her password | Reset → 204, and carol is **still locked**; `psql` shows `failed_login_attempts = 5` | Hibernate writes the whole row (verified in the brief) |
| 2 | **Then add `clearAutomatically = true`** to that query and reset again | 204, unlocked, and the **old password still works** | Clearing detaches the entity; its changes are never flushed |
| 3 | **Change without revoking the reset link**: request a reset (copy the link from the log), change the password with `PUT /users/me/password`, then confirm with the old link | 204: the account is taken back by the old link | Outstanding reset links must die when the password changes |
| 4 | **Validate the new password in the service, after consuming**: confirm with a 5-character password, then again with a good one and the same link | The second attempt → 400 `INVALID_TOKEN` | Boundary validation keeps the link alive |
| 5 | **Re-authenticate with `passwordEncoder.matches`**: 6 wrong current passwords | 6 × 400, and `failed_login_attempts` still 0 | The manager is what makes it count |
| 6 | **Mutation checks** once tests exist | Each planted bug turns a test red | See *Tests* |

**The race** (📊): request a reset for carol, copy the token from the log, then:

```bash
seq 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -d '{"token":"<TOKEN>","newPassword":"a-new-password-{}"}' localhost:8080/api/v1/auth/password-reset/confirm | sort | uniq -c
```

Exactly one 204, nineteen 400s, zero 500s.

### Build order

1. ~~**Settle D1–D5.**~~ Done 2026-09-29: all recommendations accepted (decisions 23–27).
2. **`@ValidPassword`**, used by `RegisterRequest`. Run the app: registration behaves as before.
3. **`AccountEmails`**; `RegistrationWorkflow` uses it. Register once: the verification link still appears in the log.
4. **Error codes and the three request records** (masked `toString()`).
5. **Reset request**: `PasswordService.requestReset`, `PasswordWorkflow.requestReset`, the endpoint. Run it: known, unknown, unverified and locked emails → identical 202s; a link in the log only for existing accounts.
6. **Reset confirm**: `UserAccount.resetPassword`, the service, the workflow, the endpoint. ⚠️ Deliberate failures **1** and **2** first (bulk unlock), then add `@DynamicUpdate` and do the unlock inside `resetPassword`. Then **4**.
7. **Change**: the service, the workflow, the endpoint. ⚠️ Deliberate failures **5**, then **3**.
8. **The "password changed" email** (D4) in both flows.
9. **Run it by hand:**
   - Reset for carol → link in the log → confirm → 204 → the old password gets 401, the new one 200; `psql`: `password_changed_at` moved, one `PASSWORD_RESET`, the token consumed.
   - The same link again → 400 `INVALID_TOKEN`. A second request revokes the first link.
   - Lock carol (5 wrong passwords), reset → unlocked (D1). Reset bob (unverified) → he can log in (D2), with `EMAIL_VERIFIED` recorded.
   - Change password: wrong current → 400 and the counter +1; same as current → 400 `PASSWORD_UNCHANGED`; right → 204, `PASSWORD_CHANGED`, and an outstanding reset link now fails.
   - The race command.
10. **Tests** (see below; deferred like §1.3–§1.5 if you choose).

### Tests

| Kind | Must prove |
|---|---|
| **Unit** | `@ValidPassword`: 11 characters fail, 12 pass, 73 bytes fail · request records never print secrets · `UserAccount.resetPassword`: clears the lock and counter, verifies only when unverified, returns whether it did · `PasswordService` (Mockito, fixed `Clock`): consume **before** hash; an invalid token → `INVALID_TOKEN` and no hash; `EMAIL_VERIFIED` only when it verified; change revokes the reset token · `PasswordWorkflow`: equal passwords → `PASSWORD_UNCHANGED` without calling the manager; `BadCredentialsException` / `LockedException` → `CURRENT_PASSWORD_INCORRECT`; other exceptions propagate; no email when the service throws; an email failure doesn't fail the request |
| **JPA slice** | With `@DynamicUpdate`: a bulk counter change followed by an entity password change keeps both (the brief's experiment, as a test) |
| **Web slice** | Confirm with a short password → 400 and the service never called · request with a malformed email → 400 · none of the bodies echoes a password |
| **Integration** (capturing email sender) | Request for a known email → one email with a `#token=` link; unknown → 202 and **no** email; identical bodies · confirm → 204, old password 401, new 200, `PASSWORD_RESET` recorded · same token again → 400 · confirm unlocks a locked account and verifies an unverified one · change: wrong current → 400 and counter +1; unchanged → 400; right → 204 and the old reset link → 400 · both flows send the "password changed" email (D4) |
| 📊 **Race** | 20 concurrent confirms with one token → exactly one 204, zero 500s |

**Mutation checks:** hash before consume (a mocked encoder is called for a bad token) · drop `@DynamicUpdate` (the JPA test) · don't revoke the reset token on change · re-authenticate with `matches` (the counter test) · send inside the transaction (no email on a rolled-back confirm) · verify even when already verified (the timestamp moves).

🎯 **Interview questions:**
- "Design a password-reset flow." (The six token properties, 202 always, fragment links, one live link, short TTL, consume-then-hash, same-transaction events, notify the owner, no auto-login.)
- "Why is your reset link valid for 30 minutes but your verification link for 24 hours?"
- "What happens to an outstanding reset link when the user changes their password?"
- "Why does your change-password endpoint go through the `AuthenticationManager`?"
- "Your reset unlocked the account in the database, and then the lock came back. How?" (The whole-row `UPDATE`.)
- "After a password change, what happens to the user's other devices?" (Nothing yet; Phase 2.)
- "Does your reset request reveal which emails exist? What does it still leak?" (Timing.)

---

## 1.7 — Profile ⏭️ moved to Phase 2 as a side task (2026-10-01, decision 28; the notes below are kept for then)

- [ ] `GET /api/v1/users/me` → id, email, username, displayName, timezone, role, emailVerified, createdAt. It **never** includes the password hash, the lock state or failed attempts. The response DTO decides what's public, which is Phase 0's over-exposure argument with a real hash behind it now.
- [ ] `PATCH /api/v1/users/me` with `{displayName?, timezone?}`, where `null` means "leave unchanged". 💡 A record **can't tell "field missing" from "field sent as null"**. Write that limit down: a real PATCH needs JSON Merge Patch or `JsonNullable`. Not now.
- [ ] `timezone` must be a valid IANA **region** ID. ⚠️ **Trap:** `ZoneId.of` also accepts `+05:30`, `UTC+1` and `Z`. Fixed offsets are *not* timezones, because they ignore daylight saving. Validate against `ZoneId.getAvailableZoneIds()`.
- [ ] Email change is **out of scope**: it needs re-verification of the new address, and it's a whole feature. *(2026-10-01: added to the deferred backlog, `PROJECT_CONTEXT.md` §6, with the design.)*

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

## Definition of done ✅ Phase 1 closed 2026-10-01

Check these literally, by running them. *Evidence per item: **tests** = the suite (76/76, run 2026-10-01); **reported** = your manual runs; **audited** = I read the code.*

- [x] No generated password in the startup log. The `--debug` report has been read, and the backing-off condition written down. (verified in §1.2; the back-off conditions are in the learning log)
- [x] Register → the verification link is in the dev log → verify → login 200. The `user_accounts` row shows a `{bcrypt}` hash, a lowercase email, and `created_by` = `system`. (reported, §1.3–§1.4)
- [x] Login before verification behaves exactly as decided in §1.5. (reported: 401 with a wrong password, 403 with the right one)
- [x] N wrong passwords → locked; the correct password is still rejected; after the lock duration it works. **Also tested through Basic.** (reported; by manual run, not by test)
- [x] Unknown email and wrong password give byte-identical response bodies (except `timestamp` and `correlationId`). (reported; by construction: one exception, one fixed detail)
- [x] Reset for an unknown email and for a known email → both 202 with identical bodies. (reported)
- [x] A reset token works once. The second use and the concurrent race both give exactly one success. (reported; the race numbers weren't sent)
- [x] No password, token or email appears anywhere in the logs at INFO (the `dev` email sender is the one documented exception). (audited 2026-10-01: account logs carry ids only. Note: dev's SQL bind logging at `TRACE` prints emails and hashes.)
- [x] Every row of the access table behaves as specified. The last rule is `denyAll()`. (tests)
- [x] No `JSESSIONID` cookie in any response. 401s carry `X-Correlation-Id`. (tests)
- [x] After a password change, `password_changed_at` has moved, and a `PASSWORD_CHANGED` event exists. (reported)
- [x] Suite green; the Phase 0 tests are fixed, not disabled. (tests: 76/76, 2 containers. **Nothing after §1.2 has tests**: owed, not before Phase 2.)
- [x] README updated: the auth endpoints, how to register and verify in dev (where the link appears), and the Basic auth note. (2026-10-01)
- [x] Small commits, roughly one per section. (roughly; §1.6's commit message says "1.5")

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
