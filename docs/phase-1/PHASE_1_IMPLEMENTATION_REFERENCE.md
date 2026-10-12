# Phase 1 — Implementation Reference (identity, passwords, tokens, lockout)

> **What this is:** a reusable record of *what* Phase 1 built, *how* it's built, and the *rules* it follows, written so the same design can be rebuilt in another Spring Boot project without re-deriving it. Read Part D first if you're implementing it somewhere new.
> **What it isn't:** the requirements and decisions with their alternatives (`PHASE_1_REQUIREMENTS.md`), what was learned, hit and measured (`PHASE_1_LEARNING_LOG.md`), or how to test security rules (`SECURITY_TESTING_GUIDE.md`).
> **Describes the code as it is now,** not as it was at Phase 1's close: where Phase 2 changed a Phase 1 feature, the entry says so under **Changed in Phase 2** (the details are in `../phase-2/PHASE_2_IMPLEMENTATION_REFERENCE.md`). Phase 1 is closed, so this document is updated only when a later phase changes one of its features; the changelog at the bottom records each update.

| Sub-section | Feature | Status |
|---|---|---|
| §1.1 | The security gate (filter chain, access rules) | ✅ built (`9211684`); transport changed in Phase 2 (Basic → bearer) |
| §1.2 | Accounts, passwords, the principal, the auditor | ✅ built (`1fc0f54`); request principal changed in Phase 2 |
| §1.3 | Registration | ✅ built (`02bc36b`) |
| §1.4 | Email verification (link tokens, sending after commit) | ✅ built (`5085ed7` and earlier) |
| §1.5 | Login, lockout, security event log, login history | ✅ built (`aa7efca`, `a2cd682`); login extended in Phase 2 |
| §1.6 | Password reset and change | ✅ built (`63bb1b4`, `4e115a2`); session revocation added in Phase 2 |
| §1.7 | Profile | ➡️ moved to Phase 2 (§2.6), not built |

**Stack this was built on:** Java 21 · Spring Boot 3.5.16 · Spring Security 6.5.11 · Hibernate 6.6 · PostgreSQL 17 · Flyway.

---

## Contents

- [Part A — Design principles and rules](#part-a--design-principles-and-rules)
- [Part B — The architecture at a glance](#part-b--the-architecture-at-a-glance)
- [Part C — Each feature: what, how, rules, pitfalls](#part-c--each-feature-what-how-rules-pitfalls)
  - [C1. The security gate (§1.1)](#c1-the-security-gate-11)
  - [C2. Accounts, password storage and the principal (§1.2)](#c2-accounts-password-storage-and-the-principal-12)
  - [C3. The password check and what it reveals (§1.2, §1.5)](#c3-the-password-check-and-what-it-reveals-12-15)
  - [C4. Registration (§1.3)](#c4-registration-13)
  - [C5. Link tokens: verification and reset links (§1.4)](#c5-link-tokens-verification-and-reset-links-14)
  - [C6. Email verification, resend, and sending after commit (§1.4)](#c6-email-verification-resend-and-sending-after-commit-14)
  - [C7. Login (§1.5)](#c7-login-15)
  - [C8. Lockout (§1.5)](#c8-lockout-15)
  - [C9. The security event log and login history (§1.5)](#c9-the-security-event-log-and-login-history-15)
  - [C10. Password reset (§1.6)](#c10-password-reset-16)
  - [C11. Password change (§1.6)](#c11-password-change-16)
  - [C12. Input rules: passwords, normalisation, masking (§1.2–§1.6)](#c12-input-rules-passwords-normalisation-masking-12-16)
- [Part D — Rebuilding this in another project: the recipe](#part-d--rebuilding-this-in-another-project-the-recipe)
- [Part E — Quick reference: endpoints, errors, events, configuration](#part-e--quick-reference-endpoints-errors-events-configuration)
- [Part F — Framework behaviour we relied on (checked in the sources)](#part-f--framework-behaviour-we-relied-on-checked-in-the-sources)
- [Part G — Known gaps and debt](#part-g--known-gaps-and-debt)
- [Changelog](#changelog)

---

## Part A — Design principles and rules

Every Phase 1 class follows these. Each says **what** the rule is and **why**, because a rule without its reason gets "simplified" away.

### A1. Security code is judged on the failure path

A login that returns 200 for the right password is maybe 20% of the job. The rest is what happens with a wrong password, an unknown email, the same token used twice at once, and an attacker comparing responses. Every feature below states its failure behaviour as carefully as its success.

### A2. Close everything not explicitly opened

The last access rule is `denyAll()`, not `authenticated()`: with self-registration anyone can be "authenticated", so `authenticated()` as the default would open every forgotten endpoint to every user. Public endpoints are listed exactly, for `POST` only. Specific rules come before broad ones (first match wins).

### A3. Two identifiers: people sign in with an email, the system records a username

The email is how a person signs in; the **username** (lowercase, never containing `@`) is what goes into `created_by`, mentions and logs. Audit history survives an email change, and an email can never be mistaken for a username.

### A4. The signed-in user is a small immutable principal, never the entity

The principal lives in the `SecurityContext` for the whole request, outside any transaction. An entity there would be a stale, detached copy with lazy fields waiting to throw. `TaskflowPrincipal` carries id, username, email, password hash, authorities and two status flags, and lives in `common/security` because the auditor (in `common`) reads principals and `common` never imports a feature.

### A5. Don't reveal who has an account, or in what state, to someone who doesn't know the password

Login gives the **same 401 and body** for an unknown email, a wrong password and a locked account. "Unverified" (403) is revealed only after a **correct** password. Resend and reset-request always answer 202. Registration is the one deliberate exception (409 for a taken email, because it's far clearer for real users). Timing still leaks a little (Part G).

### A6. Normalise once, at the top, and let the database refuse anything that slipped through

Emails and usernames are trimmed and lowercased with `Locale.ROOT` in one helper (`Normalize`), before any check or save, so the check and the save use the same value. `CHECK` constraints (email lowercase, username format, hash prefix, role) refuse anything a forgotten code path would store.

### A7. A link token has six properties

**Unguessable** (32 bytes from `SecureRandom`), **stored only as a SHA-256 hash**, **expiring**, **single-use** (one conditional `UPDATE`), **purpose-bound**, and **cancelled when a new one is issued** (with a partial unique index guaranteeing one live token per user and purpose). Plus: carried in the URL **fragment**, never in a log outside dev.

### A8. Know when the database actually does something

- `save()` doesn't write; the flush does, normally at commit. Use `saveAndFlush()` when a constraint error must surface **inside** your `try`.
- After a database error inside a transaction, the transaction is **finished** (rollback-only). Catch it inside only to rethrow something else; catch it to carry on only **outside** the transaction.
- Hibernate writes **every column** of a dirty entity by default; `@DynamicUpdate` (on `UserAccount`) writes only the changed ones.
- `@Modifying` queries skip the persistence context: entities already loaded keep old values.

### A9. Choose deliberately how each method joins a transaction

- `REQUIRED` (default): joins or starts one.
- `MANDATORY`: must join the caller's (token issue and consume), so a token can never commit separately from the registration, verification or reset it belongs to.
- `REQUIRES_NEW`: its own transaction (the lockout counter), so a failed login's rollback can't erase the failure count.

And **a record commits with what it records**: a state change's event (verified, locked, reset, changed) joins that change's transaction; a failed attempt's records (counter, `LOGIN_FAILED`) are saved on their own so they survive the failure.

### A10. Side effects that can't be undone happen after commit, from another class

An email can't be unsent. Workflows (`RegistrationWorkflow`, `PasswordWorkflow`) have **no** transaction: they call the transactional service (which commits on return) and only then send. It must be a **separate** class: a call through `this` skips Spring's proxy, so `@Transactional` silently does nothing.

### A11. Security counters fail loudly

The lockout listener runs inside the password check. If it can't write the counter, the request fails with a 500 rather than letting an uncounted guess through. Phase 1 hit the opposite: a listener that caught and logged database errors left lockout silently off.

### A12. Secrets and personal data never reach logs

Every record holding a password, a token (or, in most cases, an email) overrides `toString()`; account-related log lines carry the **id**. The one exception is the dev-only `LoggingEmailSender`, which logs emails so you can copy the link.

### A13. Time is injected

A `Clock` bean everywhere ("now" passed into entities and queries), so time rules are testable at the exact boundary instant without sleeping, and one operation never mixes two "nows".

### A14. The client's IP is the connection's address

`request.getRemoteAddr()`, never `X-Forwarded-For` (any client can send any value). Stored as PostgreSQL `inet` (validated, canonical); a missing or unparsable address stays `null`, never `127.0.0.1`.

### A15. `common` never imports a feature

Shared code defines interfaces and generic types (`ErrorCode`, `ApplicationException` subtypes, `EmailSender`, `TaskflowPrincipal`); features implement them. Phase 1 broke this twice and fixed it both times.

---

## Part B — The architecture at a glance

### B1. Where each piece lives

```
common/security                          user                                        user/token
───────────────                          ────                                        ──────────
TaskflowSecurityConfig (chain, beans)    UserAccount, UserAccountRepository          UserToken, UserTokenRepository
TaskflowPrincipal                        TaskflowUserDetailsService                  UserTokenService (issue, consume)
UserAuthenticationChecks (pre/post)      AuthController, UserController              TokenCodec (generate, hash, isWellFormed)
UserRole (moved here in Phase 2)         UserRegistrationService, RegistrationWorkflow  TokenProperties, TokenPurpose, IssuedToken
                                         LoginService, LoginAttemptService
common/validator                         AuthenticationEventsListener, LockoutProperties  user/event
ValidPassword, MaxUtf8Bytes              PasswordService, PasswordWorkflow           SecurityEvent (@Immutable), SecurityEventType,
                                         AccountEmails, LoginHistoryService            LoginFailureReason, SecurityEventRecorder,
common/mail                              request/response records (masked toString)    SecurityEventRepository
EmailSender, LoggingEmailSender (dev)
common/util: Normalize   common/web: ClientInfo   common/config: AuditAwareImpl, FrontendProperties
```

### B2. The flows

**Register** → `RegistrationWorkflow` (no transaction) → `UserRegistrationService.registerUser` (transaction: normalise → duplicate checks → bcrypt → `saveAndFlush` → issue verification token) → commit → send the verification email (failure logged, still 201).

**Verify** → `UserRegistrationService.verify` (transaction: consume token → mark verified → record `EMAIL_VERIFIED`) → 204.

**Resend** → `RegistrationWorkflow.resendVerificationEmail` → `resend` (transaction: unknown or verified → nothing; else issue, revoking the old link) → commit → send → always 202 (a concurrent-issue constraint violation is caught outside the transaction and also becomes 202).

**Login** → `LoginService` (no transaction) → `AuthenticationManager` → `DaoAuthenticationProvider`: load user → pre-check (locked?) → bcrypt compare → post-check (verified?) → success / failure event → listener updates the lockout counter (own transaction) → `LoginService` records the event and answers. *(Phase 2 then starts a session and issues tokens.)*

**Wrong password** → `AuthenticationFailureBadCredentialsEvent` → `AuthenticationEventsListener` → `LoginAttemptService.recordFailure` (`REQUIRES_NEW`: increment in SQL → lock if the threshold is reached → `ACCOUNT_LOCKED` once) → `LoginService` records `LOGIN_FAILED` → 401.

**Reset request** → `PasswordWorkflow.requestReset` → `PasswordService.requestReset` (transaction: unknown email → nothing; else issue a 30-minute reset token) → commit → email the stored address → always 202.

**Reset confirm** → `PasswordService.confirmReset` (transaction: consume token → set password, clear the lock, verify the email if needed → events → *Phase 2: revoke every session*) → commit → "password changed" email → 204.

**Change** → `PasswordWorkflow.changePassword` → re-authenticate the **current** password through the manager (counts toward lockout) → `PasswordService.applyChange` (transaction: set password → revoke any reset link → event → *Phase 2: revoke every other session*) → commit → email → 204.

**Login history** → `GET /users/me/login-history` → the caller's `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `ACCOUNT_LOCKED` events, paged, newest first.

### B3. The schema (V2–V4)

| Table | One row per | Constraints and indexes that matter |
|---|---|---|
| `user_accounts` (V2) | person | unique `email`, unique `username`; `email = lower(email)`; email format; username `^[a-z0-9][a-z0-9._-]{1,48}[a-z0-9]$` (3–50 chars, no `@`); display name not blank; `password_hash` must start with `{id}` and is `varchar(200)` (room for argon2/pbkdf2); `role in ('USER','ADMIN')`; `failed_login_attempts >= 0`; `locked_until`, `email_verified_at`, `password_changed_at` |
| `user_tokens` (V3) | issued link | unique `token_hash`; hash format `^[0-9a-f]{64}$` (a raw token can never be stored); `purpose in ('EMAIL_VERIFICATION','PASSWORD_RESET')`; at most one of `consumed_at` / `revoked_at`; FK `ON DELETE CASCADE` + index; **partial unique index: one active token per user and purpose** |
| `security_events` (V4) | security event | fixed list of event types (extended forward-only by Phase 2's V6); `failure_reason` present **if and only if** `LOGIN_FAILED`; `ip_address inet`; FK `ON DELETE CASCADE`; index `(user_account_id, occurred_at, id)` for history; **a trigger rejects every `UPDATE`** (append-only); `DELETE` allowed (cascade, retention) |

Every constraint is named (the application maps errors by constraint name; PostgreSQL truncates names over 63 characters). Applied migrations are frozen.

---

## Part C — Each feature: what, how, rules, pitfalls

### C1. The security gate (§1.1)

**What it does.** Puts every request through Spring Security, which lets it through, answers 401 ("I don't know who you are") or 403 ("I know who you are, and no").

**How it's built.** One `SecurityFilterChain` bean in `TaskflowSecurityConfig`:
- `POST` only, exact paths, `permitAll`: register, login, verify-email, verify-email/resend, password-reset/request, password-reset/confirm (Phase 2 adds refresh and logout).
- `EndpointRequest.to("health", "info")` → `permitAll` (probes are anonymous); `EndpointRequest.to("metrics")` → `hasRole("ADMIN")`; health **details** restricted by `management.endpoint.health.show-details: when_authorized` + `roles: ADMIN`.
- `/error` → `permitAll` (Tomcat's internal error dispatch passes through security too).
- `/api/v1/**` → `authenticated()`; `anyRequest()` → `denyAll()`.
- Stateless sessions; CSRF off with the reason written next to it; Spring's `LogoutFilter` disabled.

**Rules.**
- Rules are checked top to bottom; the first match wins.
- `permitAll()` lets everyone in; `anonymous()` lets in **only** callers who aren't signed in (a signed-in admin gets 403).
- Actuator rules by endpoint **name** (`EndpointRequest`), so they keep matching if paths move.
- Keep health public and protect its details: a probe that needs a login makes the orchestrator restart the app in a loop.
- `denyAll()` gives an anonymous caller 401, not 403: it can't forbid someone it hasn't identified.

**Pitfalls.**
- URL patterns are checked only when a request arrives: `"/api/v1/auth/{register, login}"` (a path *variable*, not a list) and `EndpointRequest.to("health/**")` both started cleanly and turned every POST / GET into a 500.
- `show-details: when_authorized` without `roles` shows details to **any** signed-in user.
- Declaring any `SecurityFilterChain` removes Boot's default chain; declaring a user service, provider or manager removes the generated user (test credentials silently stop working).
- Default filters you didn't ask for (`LogoutFilter`) run **before** your rules.

**Changed in Phase 2.** HTTP Basic was removed; requests authenticate with a bearer token; filter-chain 401/403 are now `ProblemDetail` bodies (entry point + access-denied handler); CSRF's comment rewritten; CORS added.

**Files.** `common/security/TaskflowSecurityConfig`; `application.yml` (`management.*`).

---

### C2. Accounts, password storage and the principal (§1.2)

**What it does.** Stores people, their passwords and their platform role, and gives Spring Security a way to load them.

**How it's built.**
- `UserAccount` (`BaseEntity`, `@DynamicUpdate`): email, username, display name, timezone, `{bcrypt}` hash, role (`USER`/`ADMIN`, one column), `emailVerifiedAt`, `failedLoginAttempts`, `lockedUntil`, `passwordChangedAt`. Created only through `createUserAccount(...)` (role `USER`, timezone `UTC` set in Java); changed only through entity methods (`markEmailVerified`, `changePassword`, `resetPassword`).
- `PasswordEncoder` = `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (bcrypt by default, hashes stored as `{bcrypt}$2a$10$…`).
- `TaskflowUserDetailsService.loadUserByUsername(email)`: normalises the email, loads the account, builds a `TaskflowPrincipal` with `enabled` = email verified, `accountNonLocked` = no lock or the lock has passed (`!now.isBefore(lockedUntil)`, injected `Clock`), and one authority `ROLE_<role>`.
- One `DaoAuthenticationProvider` **bean** with our pre/post checks; the `AuthenticationManager` bean just returns the one Spring builds from it.
- `AuditAwareImpl` writes the caller's app username to `created_by`/`updated_by`, and `system` for anything else (including the anonymous token, which claims to be authenticated).

**Rules.**
- One platform role per user; "many roles per user" belongs to organisation and project membership (Phases 3–4).
- The first admin is made by hand (`UPDATE user_accounts SET role = 'ADMIN' …`), never seeded by a migration (that would ship a known admin password).
- Authorities carry the `ROLE_` prefix, because `hasRole("ADMIN")` looks for `ROLE_ADMIN`.
- The `{id}` prefix lets you move to a new algorithm later: change the default for new hashes and re-hash on the next successful login; old hashes keep working.
- Set defaults in Java: Hibernate inserts every column, so a database `DEFAULT` never applies.

**Pitfalls.**
- Since Spring Security 6.3, `UserDetails.isEnabled()` / `isAccountNonLocked()` default to `true`: fields that aren't returned by overrides do nothing, and locked or unverified users sign in.
- `getUsername()` (and so `authentication.getName()`) returns the **email**: an auditor using it fills `created_by` with personal data.
- `new ProviderManager(provider)` (the documentation's pattern) has no event publisher, so lockout events never fire, and Basic wouldn't use it.

**Changed in Phase 2.** The **request** principal is `AuthenticatedUser` (built from the access token); `TaskflowPrincipal` remains only while a password is being checked. The auditor reads `AuthenticatedUser`. `UserRole` moved to `common/security`.

**Files.** `user/UserAccount`, `UserAccountRepository`, `TaskflowUserDetailsService`; `common/security/TaskflowPrincipal`, `TaskflowSecurityConfig`, `UserRole`; `common/config/AuditAwareImpl`; `db/migration/V2__create_user_accounts.sql`.

---

### C3. The password check and what it reveals (§1.2, §1.5)

**What it does.** Checks a password through one pipeline, in an order that doesn't leak account state.

**How it's built.** `DaoAuthenticationProvider`: load the user → **pre-checks** → compare the password → **post-checks**.
- `UserPreAuthenticationChecks`: locked → `LockedException` (mapped to the same 401 as a wrong password).
- `UserPostAuthenticationChecks`: not verified → `DisabledException` (403, only reachable with the right password).
- Spring's account-expired and credentials-expired checks are dropped (not used).

**Rules.**
- "Unverified" moved to the **post**-checks: Spring's default puts it before the password, so anyone with only an email learns the account's state.
- "Locked" stays a **pre**-check with the generic 401: revealing it only after a correct password would let an attacker recognise the right guess during a lockout.
- Spring hides "user not found" as "bad credentials" and hashes a dummy password for unknown users, so timing matches. Don't undo that anywhere else.

**Files.** `common/security/UserAuthenticationChecks`, `TaskflowSecurityConfig#daoAuthenticationProvider`.

---

### C4. Registration (§1.3)

**What it does.** `POST /api/v1/auth/register` creates an **unverified** account and sends a verification link. 201 with the account; 409 naming the field if the email or username is taken.

**How it's built.** `UserRegistrationService.registerUser` (`@Transactional`):
1. Normalise email and username (C12).
2. `existsByEmail` → 409 `EMAIL_ALREADY_REGISTERED` (`field: email`); `existsByUsername` → 409 `USERNAME_TAKEN` (`field: username`).
3. bcrypt the password; `createUserAccount`.
4. `saveAndFlush` inside a `try`: a `DataIntegrityViolationException` on `uk_user_accounts_email` / `uk_user_accounts_username` (two people racing) becomes the **same** 409.
5. Issue a verification token (C5) in the same transaction.

`RegistrationWorkflow.registerAndSendEmail` sends the email after commit (C6). Response: `UserAccountResponse {id, email, username, displayName, emailVerified, createdAt}`, **no `Location` header** (there's no URL where a caller could read another user).

**Rules.**
- Cheap checks (validation, duplicates) before bcrypt: garbage requests don't burn ~100 ms of CPU each.
- Normalise **before** the duplicate check, so `Alice@Example.com` vs `alice@example.com` is caught by the check, not by the database with an arbitrary error code.
- Registration reveals a taken email deliberately (decision 7); rate limiting (Phase 10) blunts mass probing.

**Pitfalls.**
- With `save()` instead of `saveAndFlush()`, the race's constraint error appears at commit, outside the `try`, as a 500.

**Files.** `user/UserRegistrationService#registerUser`, `RegistrationWorkflow`, `RegisterRequest`, `UserAccountResponse`, `RegistrationResult`.

---

### C5. Link tokens: verification and reset links (§1.4)

**What it does.** Issues and consumes single-use links for email verification (24 h) and password reset (30 min).

**How it's built.**
- `TokenCodec`: `generate()` = 32 bytes from `SecureRandom`, Base64URL without padding (43 chars); `hash()` = SHA-256 hex; `isWellFormed()` = exactly 43 base64url characters (garbage never reaches the database).
- `UserTokenService.issue(account, purpose)` (`MANDATORY`): revoke the account's active token for that purpose (`revoked_at`), insert the new hash with `expires_at = now + ttl(purpose)`, return `IssuedToken(raw, expiresAt)` (masked `toString()`).
- `UserTokenService.consume(raw, purpose)` (`MANDATORY`): shape check → **one conditional `UPDATE`** (`consumed_at = now WHERE hash = ? AND purpose = ? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > now`) → 0 rows = 400 `INVALID_TOKEN` (one error for every bad token) → load the token with its account.
- One `user_tokens` table for both purposes (same rules); `TokenProperties` holds both lifetimes.

**Rules.**
- SHA-256, not bcrypt: a 256-bit random token can't be guessed at any speed, and a deterministic hash is needed to **look the row up**.
- Single use under concurrency comes from the conditional `UPDATE` (exactly one request gets 1 row), not from "find, check, set".
- One live link per user and purpose, guaranteed by the partial unique index `uk_user_tokens_active`; old links are revoked (rows kept for history), not deleted.
- A link lives as long as its worst case is acceptable: 24 h for verification (a wrongly verified address), 30 min for reset (a stolen account).
- The token travels after `#` in the link: browsers never send the fragment to a server, so it stays out of access logs, proxy logs and `Referer`. The frontend reads it and POSTs it; mail scanners that pre-open links can't consume it.

**Pitfalls.**
- Two concurrent issues for one user can collide on `uk_user_tokens_active`; the transaction is then rollback-only, so the caller must handle it **outside** (C6).
- `user_tokens` keeps revoked and consumed rows until Phase 9's cleanup.

**Files.** `user/token/TokenCodec`, `UserTokenService`, `UserToken`, `UserTokenRepository`, `TokenProperties`, `TokenPurpose`, `IssuedToken`; `db/migration/V3__create_user_tokens.sql`.

---

### C6. Email verification, resend, and sending after commit (§1.4)

**What it does.** `POST /auth/verify-email {token}` verifies the account (204); `POST /auth/verify-email/resend {email}` sends a fresh link and always answers 202.

**How it's built.**
- `UserRegistrationService.verify` (`@Transactional`): consume the token → `markEmailVerified(now)` → record `EMAIL_VERIFIED` in the same transaction.
- `UserRegistrationService.resend` (`@Transactional`): unknown or already verified → nothing; otherwise issue (revoking the previous link). No `try` inside.
- `RegistrationWorkflow.resendVerificationEmail` (no transaction): calls `resend`; catches a `DataIntegrityViolationException` on `uk_user_tokens_active` (a concurrent resend won) and returns normally; then sends.
- `AccountEmails` builds the links from `FrontendProperties.frontendBaseUrl()` (`/verify-email#token=…`, `/reset-password#token=…`) and sends through the `EmailSender` interface. A send failure is logged at ERROR with the **account id** and never propagated.
- `EmailSender` in dev: `LoggingEmailSender` (`@Profile("dev")` + `@Component`). There is **no** prod sender yet, so `prod` refuses to start until Phase 9 adds one (on purpose).

**Rules.**
- Send only after the transaction committed, from a class with no transaction: an email sent inside could describe data that then rolls back (a "phantom email"), and it would hold the transaction open for the mail server.
- An email failure after the account was saved still answers 201: the account exists and resend is the recovery path.
- Resend reveals nothing: 202 for unknown, verified and unverified alike.

**Pitfalls.**
- Catching the constraint violation **inside** `resend` and returning normally makes the commit fail with `UnexpectedRollbackException` (500).
- `@Profile` only works on a bean: the class also needs `@Component`.
- The base URL comes from config, never hard-coded `localhost`.

**Files.** `user/UserRegistrationService` (`verify`, `resend`), `RegistrationWorkflow`, `AccountEmails`; `common/mail/EmailSender`, `LoggingEmailSender`, `EmailMessage`; `common/config/FrontendProperties`.

---

### C7. Login (§1.5)

**What it does.** `POST /api/v1/auth/login {email, password}` checks the password and answers without revealing account state (A5).

**How it's built.** `LoginService.login` (deliberately **not** transactional):
1. Normalise the email; build an unauthenticated token with `WebAuthenticationDetails(ip, null)` so the failure listener gets the IP.
2. `authenticationManager.authenticate(...)`:
   - `BadCredentialsException` → record `LOGIN_FAILED (BAD_CREDENTIALS)` → 401 `AUTHENTICATION_FAILED`;
   - `LockedException` → record `LOGIN_FAILED (ACCOUNT_LOCKED)` → **the same** 401;
   - `DisabledException` → record `LOGIN_FAILED (EMAIL_NOT_VERIFIED)` → 403 `EMAIL_NOT_VERIFIED`;
   - anything else (database down) propagates → 500, never "wrong password".
3. Failures are recorded only for emails that belong to an account (`findIdByEmail`); unknown emails record nothing.
4. Success → `LOGIN_SUCCEEDED` → load the account → respond.

**Rules.**
- Not transactional, so the failure counter (own transaction) and `LOGIN_FAILED` survive the failed authentication.
- Translate only the three expected exceptions; an outage must look like an outage.
- Identical 401 bodies for unknown, wrong and locked.

**Changed in Phase 2.** After authentication: one clock reading → start a session → record `LOGIN_SUCCEEDED` → issue an access token; the response is `{accessToken, tokenType, expiresIn, user}` plus the refresh-token cookie. The Basic "side door" (passwords on every request, also counting toward lockout) is gone.

**Files.** `user/LoginService`, `AuthController#login`, `LoginRequest`; `common/error/LoginFailedException`.

---

### C8. Lockout (§1.5)

**What it does.** Five wrong passwords lock the account for 15 minutes; it unlocks automatically; the counter restarts at zero after a lock; a success resets it.

**How it's built.**
- `AuthenticationEventsListener` reacts to Spring Security's events: `AuthenticationFailureBadCredentialsEvent` → `LoginAttemptService.recordFailure(email, ip)`; `AuthenticationSuccessEvent` whose principal is a `TaskflowPrincipal` → `recordSuccess(id)`.
- `LoginAttemptService.recordFailure` (`REQUIRES_NEW`): find the id by normalised email (unknown → nothing) → `incrementFailedLoginAttempts` (`SET n = n + 1` in SQL) → `lockIfThresholdReached` (one conditional `UPDATE` that sets `locked_until = now + 15 min` **and** resets the counter where `n >= 5`) → if that returned 1 row, record `ACCOUNT_LOCKED` once, in the same transaction.
- `recordSuccess` (`REQUIRES_NEW`): a conditional reset that writes only when there's something to reset.
- The lock is enforced by the pre-check (C3); expiry is a time comparison, so unlocking needs no job.

**Rules.**
- Event-driven, not inside the login endpoint, so **every** password check counts (in Phase 1 that included Basic; since Phase 2, login and change-password).
- Increment in SQL, not "read, add one, save": two simultaneous failures can't both write 4.
- The lock's row count tells exactly one request that **it** applied the lock, so `ACCOUNT_LOCKED` is recorded once.
- Only wrong passwords count; lockout caps guessing speed on one account, and throttling the attacker is Phase 10's rate limiter. Lockout revokes no sessions (D7).

**Pitfalls.**
- The counter in the login's own transaction: the failed login rolls it back, it stays 0 and nothing ever locks, with no error.
- A listener that catches and logs database errors: lockout silently off.
- A derived query `Optional<Long> findIdByEmail(String)` loads the whole entity (the words between `find` and `By` are ignored) and fails only for existing emails; write `@Query("select u.id from UserAccount u where u.email = :email")`.
- Anyone who knows your email can lock you out; the auto-unlock limits it, rate limiting (Phase 10) fixes it.
- `REQUIRES_NEW` needs a second connection while the first is paused, which is another reason the login flow itself has no transaction.

**Changed in Phase 2.** A success event now fires on **every** bearer request; the listener's `TaskflowPrincipal` check is what keeps those from resetting the counter (and must stay).

**Files.** `user/AuthenticationEventsListener`, `LoginAttemptService`, `LockoutProperties`, `UserAccountRepository` (`incrementFailedLoginAttempts`, `lockIfThresholdReached`, `resetFailedLoginAttempts`, `findIdByEmail`).

---

### C9. The security event log and login history (§1.5)

**What it does.** Records security-relevant events in an append-only table and shows a user their sign-in history.

**How it's built.**
- `SecurityEvent` (`IdentifiedEntity`, `@Immutable`): account id, type, failure reason, `occurredAt`, `InetAddress` IP, user agent. One static factory per type; only `loginFailed` accepts a reason.
- `SecurityEventRecorder`: one method per type, using `ClientInfo` (IP from the connection, user agent truncated to 512) and the injected clock.
- `LoginHistoryService.getLoginHistory` → `findByUserAccountIdAndEventTypeIn(id, {LOGIN_SUCCEEDED, LOGIN_FAILED, ACCOUNT_LOCKED}, pageable)`, sorted `occurredAt desc, id desc` through `PageableFactory` (allowed fields `occurredAt`, `id`).
- `GET /api/v1/users/me/login-history` → `PageResponse<LoginHistoryResponse {occurredAt, eventType, failureReason, ipAddress, userAgent}>`; the account id comes from the principal, never the URL.

**Rules.**
- Append-only twice: `@Immutable` (Hibernate ignores changes, silently) **and** a database trigger that rejects any `UPDATE`, loudly, whoever sends it.
- Failure reason present if and only if `LOGIN_FAILED` (a `CHECK`); events for emails with no account aren't stored.
- A state change's event commits with the change; a failed attempt's event commits on its own (A9).
- Retention stance: 12 months, enforced by Phase 9's cleanup job (IPs are personal data).

**Pitfalls.**
- Factories that accept a failure reason for every type let a caller build a row the database rejects (a 500 at the exact moment an account locks).
- `InetAddress.getByName(null)` returns the loopback address: a missing IP must stay `null`.
- Boot turns on forwarded-header handling by itself on detected cloud platforms (Kubernetes); trust forwarded headers only behind a known proxy (Phase 11).

**Changed in Phase 2.** IP parsing moved into `ClientInfo.inetAddress()`; three new event types (`SESSION_REVOKED`, `ALL_SESSIONS_REVOKED`, `REFRESH_TOKEN_REUSED`) by V6, recorded with `MANDATORY`.

**Files.** `user/event/*`; `user/LoginHistoryService`, `LoginHistoryResponse`, `UserController#getLoginHistory`; `common/web/ClientInfo`; `db/migration/V4__create_security_events.sql`.

---

### C10. Password reset (§1.6)

**What it does.** `POST /auth/password-reset/request {email}` always answers 202 and emails a 30-minute link to the stored address. `POST /auth/password-reset/confirm {token, newPassword}` sets the new password (204).

**How it's built.**
- `PasswordWorkflow.requestReset` → `PasswordService.requestReset` (`@Transactional`: unknown email → nothing; else issue a `PASSWORD_RESET` token) → commit → email. A concurrent issue colliding on `uk_user_tokens_active` is caught outside the transaction and answered 202.
- `PasswordService.confirmReset` (`@Transactional`): consume the token → `account.resetPassword(hash, now)`, which sets the password and `passwordChangedAt`, clears the counter and `lockedUntil`, and verifies the email if it wasn't → if it verified, revoke the active verification link and record `EMAIL_VERIFIED` → record `PASSWORD_RESET`. Then `PasswordWorkflow` sends "your password was changed" after commit.

**Rules.**
- Always 202 on request, whatever the email (no enumeration); the link goes to the **stored** address, never one from the request.
- The new password is validated (`@ValidPassword`) before the link is used, and the link is consumed before bcrypt runs: a typo doesn't burn the link, and garbage links don't cost a hash.
- A reset **unlocks** the account (mailbox control beats any guess) and **verifies** the email (clicking a link sent there is the proof verification asks for).
- Everything commits together; the owner is notified after commit (a takeover doesn't go unnoticed).
- Changes go through an entity method, with `@DynamicUpdate` on `UserAccount`.

**Pitfalls.**
- Unlocking with the lockout's SQL query and then setting the password on the entity **put the lock back** (the entity wrote its stale `locked_until`). `clearAutomatically = true` instead made Hibernate forget the entity, so **the password wasn't saved**. Neither raised an error. Hence `@DynamicUpdate` and entity methods only.

**Changed in Phase 2.** `confirmReset` also revokes **every** session of the account (`PASSWORD_RESET`), in the same transaction (`SessionService.revokeAll`, `MANDATORY`).

**Files.** `user/PasswordService` (`requestReset`, `confirmReset`), `PasswordWorkflow`, `UserAccount#resetPassword`, `PasswordResetRequest`, `PasswordResetConfirmRequest`, `AccountEmails`.

---

### C11. Password change (§1.6)

**What it does.** `PUT /api/v1/users/me/password {currentPassword, newPassword}` changes a known password (204).

**How it's built.** `PasswordWorkflow.changePassword`:
1. New equals current → 400 `PASSWORD_UNCHANGED` (`field: newPassword`).
2. Re-authenticate the **current** password through the `AuthenticationManager` (same pipeline, same events): wrong or locked → 400 `CURRENT_PASSWORD_INCORRECT` (`field: currentPassword`).
3. `PasswordService.applyChange` (`@Transactional`): `changePassword(hash, now)` → revoke any active reset link (an old link can't undo the change) → record `PASSWORD_CHANGED`.
4. Email the owner after commit.

**Rules.**
- Ask for the current password even though the user is signed in: sessions and tokens can be stolen.
- Check it **through the manager**, so a wrong guess counts toward lockout (closing "stolen token + unlimited guesses").
- 400, not 401: a 401 would make a client think the user was signed out.

**Pitfalls.**
- Passing the stored hash (`principal.getPassword()`) as if it were the typed password: every change fails, each failure counts, and the user locks themselves out.

**Changed in Phase 2.** The caller's email is loaded by id (`findEmailById`), because the bearer principal carries none; `applyChange` also revokes every **other** session (`PASSWORD_CHANGED`), keeping the current one.

**Files.** `user/PasswordWorkflow#changePassword`, `PasswordService#applyChange`, `UserAccount#changePassword`, `ChangePasswordRequest`, `UserController#changePassword`.

---

### C12. Input rules: passwords, normalisation, masking (§1.2–§1.6)

**What it does.** Keeps every password, email and username rule in one place.

**How it's built.**
- `@ValidPassword` = `@NotBlank` + `@Size(min = 12)` + `@MaxUtf8Bytes(72)`, composed into one annotation and used by registration, reset and change.
- `@MaxUtf8Bytes` + `MaxUtf8BytesValidator`: counts **UTF-8 bytes**, treats `null` as valid (presence is `@NotBlank`'s job).
- Login and "current password" fields check only `@NotBlank` + `@MaxUtf8Bytes(72)`, never the minimum, so tightening the rule can't lock out existing passwords.
- `Normalize.normalizeEmail` / `normalizeUsername`: `strip()` + `toLowerCase(Locale.ROOT)`.
- Messages in `ValidationMessages.properties` (plural).
- Request records with a password or token override `toString()`.

**Rules.**
- Length is what makes a password strong; no composition rules. 72 **bytes** is bcrypt's real limit.
- `Locale.ROOT`: under a Turkish locale `I` lowercases to a dotless `ı`, silently creating a different email.

**Pitfalls.**
- `@Size(max = 72)` counts Java characters: 19 emoji are 38 characters but 76 bytes. On Spring Security 6.5.11 an over-long password makes `encode()` throw (a 500); on 6.3.1 it was silently truncated, so a different password with the same first 72 bytes signed in.
- A composed constraint's parts must allow `ElementType.ANNOTATION_TYPE`.
- `ValidationMessages.properties` in the singular: users see the raw `{…}` key.

**Files.** `common/validator/ValidPassword`, `MaxUtf8Bytes`, `MaxUtf8BytesValidator`; `common/util/Normalize`; `src/main/resources/ValidationMessages.properties`; request records in `user/`.

---

## Part D — Rebuilding this in another project: the recipe

**Prerequisites:** a Spring Boot app with Flyway, a `ProblemDetail` error handler with an `ErrorCode` interface, a correlation-id filter, an injected `Clock`, `BaseEntity` + auditing, `PageableFactory`-style paging.

1. **Security starter and gate:** one `SecurityFilterChain`; public auth endpoints `POST`-only and exact; actuator rules by name (health public, details admin-only); `/error` permitted; `/api/**` authenticated; `denyAll()` last; stateless; CSRF off with the reason; default logout off. Test the rules as a table (one row per rule, slice test).
2. **Accounts:** `user_accounts` with lowercase/format/prefix/role `CHECK`s and named unique constraints; entity with a factory and change methods, defaults in Java, `@DynamicUpdate`; `DelegatingPasswordEncoder`.
3. **Principal and user service:** a small immutable principal in the shared package with `isEnabled`/`isAccountNonLocked` **overridden**; a user service that normalises and computes the flags with the `Clock`; `ROLE_` authorities.
4. **One provider bean** with custom pre-checks (locked → generic) and post-checks (unverified → only after the password); an `AuthenticationManager` bean returning the one Spring builds.
5. **Auditor** reading the principal's username, `system` otherwise (including anonymous).
6. **Input rules:** a composed password annotation (min length, max UTF-8 bytes); one normalisation helper; masked `toString()` on every secret-holding record.
7. **Registration:** normalise → duplicate checks → hash → `saveAndFlush` in a `try` mapping the unique constraints to the same 409 → issue a verification token → send after commit from a non-transactional workflow.
8. **Link tokens:** one table (hash, purpose, expiry, consumed/revoked, one-active partial unique index); a codec (32 random bytes, SHA-256, shape check); `MANDATORY` issue (revoke old, insert new) and consume (one conditional `UPDATE`, one error for every bad token); link in the URL fragment.
9. **Verification and resend:** consume + mark + event in one transaction; resend always 202, with the concurrent-issue violation caught outside the transaction.
10. **Email:** an `EmailSender` interface; a dev logging sender; a prod sender (or a deliberate start-up failure until one exists); send failures logged with the account id only.
11. **Login:** not transactional; the three expected exceptions translated (same 401 for wrong/locked/unknown, 403 for unverified), everything else propagated; events recorded for known accounts only.
12. **Lockout:** listener on authentication events; `REQUIRES_NEW` counter incremented in SQL; conditional lock with a row count that records the lock once; success reset only for password principals; fail loudly.
13. **Security event log:** append-only (`@Immutable` + trigger), `inet` IPs, reason-iff-failed `CHECK`, one factory per type, cascade delete, an index for the history query; a paged history endpoint scoped to the principal.
14. **Reset:** always-202 request to the stored address; confirm consumes the link, sets the password, unlocks, verifies, records events, all in one transaction; notify the owner after commit.
15. **Change:** re-authenticate the current password through the manager (400 on failure); set the password, revoke any reset link, record the event in one transaction; notify after commit.
16. **Prove it:** the access table; identical 401 bodies; unverified only after the right password; lockout through every password path, auto-unlock at the exact instant; 20 parallel uses of one link → exactly one success; the registration race → 409, never 500; no plaintext password in any log.

**Values we used:** password 12 characters to 72 bytes · bcrypt cost 10 · verification link 24 h · reset link 30 min · lockout 5 failures for 15 min · event retention 12 months (stance).

---

## Part E — Quick reference: endpoints, errors, events, configuration

### Endpoints (Phase 1 features, as they behave now)

| Method | Path | Auth | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/auth/register` | none | 201 `UserAccountResponse` (no `Location`) | 400 `VALIDATION_FAILED` · 409 `EMAIL_ALREADY_REGISTERED` / `USERNAME_TAKEN` (`field`) |
| POST | `/api/v1/auth/verify-email` | none (token in body) | 204 | 400 `INVALID_TOKEN` |
| POST | `/api/v1/auth/verify-email/resend` | none | 202 always | 400 `VALIDATION_FAILED` |
| POST | `/api/v1/auth/login` | none (password in body) | 200 (Phase 2 body: tokens + `user`) | 400 · 401 `AUTHENTICATION_FAILED` · 403 `EMAIL_NOT_VERIFIED` |
| POST | `/api/v1/auth/password-reset/request` | none | 202 always | 400 `VALIDATION_FAILED` |
| POST | `/api/v1/auth/password-reset/confirm` | none (token in body) | 204 | 400 `INVALID_TOKEN` / `VALIDATION_FAILED` |
| PUT | `/api/v1/users/me/password` | bearer (Phase 2) | 204 | 400 `PASSWORD_UNCHANGED` / `CURRENT_PASSWORD_INCORRECT` (`field`) |
| GET | `/api/v1/users/me/login-history?page&size` | bearer (Phase 2) | 200 page of `{occurredAt, eventType, failureReason, ipAddress, userAgent}` | 401 |
| GET | `/actuator/health`, `/info` | none (details: ADMIN) | 200 | — |
| GET | `/actuator/metrics` | ADMIN | 200 | 401 / 403 |

### Error codes (`UserErrorCode`)

`EMAIL_ALREADY_REGISTERED` 409 · `USERNAME_TAKEN` 409 · `INVALID_TOKEN` 400 · `AUTHENTICATION_FAILED` 401 · `EMAIL_NOT_VERIFIED` 403 · `PASSWORD_UNCHANGED` 400 · `CURRENT_PASSWORD_INCORRECT` 400.

### Security events (Phase 1's six)

| Type | Recorded when | Transaction |
|---|---|---|
| `LOGIN_SUCCEEDED` | successful login | its own |
| `LOGIN_FAILED` (+ reason `BAD_CREDENTIALS` / `ACCOUNT_LOCKED` / `EMAIL_NOT_VERIFIED`) | failed login for a known account | its own (survives the failure) |
| `ACCOUNT_LOCKED` | the failure that applied the lock | the counter's `REQUIRES_NEW` |
| `EMAIL_VERIFIED` | verification, or a reset that verified | with the verification |
| `PASSWORD_RESET` | reset confirmed | with the reset |
| `PASSWORD_CHANGED` | password changed | with the change |

### Configuration

| Property | Value | Notes |
|---|---|---|
| `taskflow.security.tokens.email-verification-ttl` | `24h` | required, positive |
| `taskflow.security.tokens.password-reset-ttl` | `30m` | required, positive |
| `taskflow.security.lockout.max-failed-attempts` | `5` | at least 1 |
| `taskflow.security.lockout.duration` | `15m` | positive |
| `taskflow.app.frontend-base-url` | dev/test `http://localhost:3000`; prod `${FRONTEND_BASE_URL}` | builds the email links |
| `management.endpoint.health.show-details` / `.roles` | `when_authorized` / `ADMIN` | both needed |

---

## Part F — Framework behaviour we relied on (checked in the sources)

Spring Security 6.5.11 / Boot 3.5.16 / Hibernate 6.6, read in `~/.m2` or tested on a copy of the project.

| Behaviour | Consequence for the design |
|---|---|
| `DaoAuthenticationProvider`: load user → pre-checks → password compare (also when a pre-check fails, so timing looks normal) → post-checks; defaults check locked/disabled **before** the password | Custom checks: locked before (generic 401), unverified after |
| "User not found" is hidden as bad credentials, and a dummy password is hashed for unknown users | Login timing roughly matches; don't add work only for known emails |
| Since 6.3, `UserDetails.isEnabled()` / `isAccountNonLocked()` default to `true` | The principal must override them |
| `UserDetails.getUsername()` = whatever the user signs in with (the email); `Authentication.getName()` calls it | The auditor reads the app username from our principal |
| Boot builds the main `AuthenticationManager` from a single `AuthenticationProvider` bean and gives it an event publisher; a hand-made `ProviderManager` has none | One provider bean; the manager bean returns Spring's |
| One authentication event per check, published synchronously before the request continues; the failure event carries the username as typed | The listener normalises the email and runs in the request |
| Declaring a `SecurityFilterChain` removes Boot's default chain; declaring a user service / provider / manager removes the generated user | Tests need real users (`TestUsers`) |
| URL patterns are validated per request, not at startup; `{…}` is a path variable; `EndpointRequest.to(...)` takes endpoint names | Test every access rule |
| Tomcat's `sendError` makes an internal `/error` dispatch that passes through security again | `/error` is `permitAll` |
| `LogoutFilter` is on by default and runs before the access rules | Disabled until a real logout exists |
| `encode()` throws for passwords over 72 bytes (6.5.11); 6.3.1 silently truncated | `@MaxUtf8Bytes(72)` everywhere a password enters |
| A database error inside a transaction marks it rollback-only; catching it and returning normally → `UnexpectedRollbackException` at commit | Catch-to-continue only outside the transaction (workflows) |
| `@Transactional` on a method called through `this` does nothing (proxy skipped) | Workflows and services are separate beans |
| Hibernate writes every column on update unless `@DynamicUpdate`; `@Modifying` queries bypass the persistence context; `clearAutomatically` detaches loaded entities | `@DynamicUpdate` on `UserAccount`, changes through entity methods |
| Spring Data ignores the words between `find` and `By`: `findIdByEmail` returns the entity | Projection by `@Query` |
| Hibernate inserts every mapped column, unset ones as `NULL`, so database defaults don't apply | Defaults set in Java |
| `ddl-auto: validate` doesn't check primary keys or nullability against the mapping | Constraints reviewed by hand |
| A record's accessor returns `null` inside its own compact constructor | Validate the parameter, not the accessor |
| `@Profile` only works on a bean | `@Profile("dev") @Component` |
| PostgreSQL truncates identifiers over 63 characters; `inet` canonicalises (`::1` = `0:0:0:0:0:0:0:1`) | Short constraint names; IPs as `inet` |
| `InetAddress.getByName(null)` returns loopback; Boot enables forwarded headers on detected cloud platforms | Keep a missing IP `null`; trust forwarded headers only behind a known proxy |

---

## Part G — Known gaps and debt

**Tests owed** (deferred by decision, raised again at Phase 2's close): §1.3 registration, §1.4 verification, §1.5 login/lockout/history, §1.6 reset/change (planned lists in `PHASE_1_REQUIREMENTS.md`); the Phase 0 "error bodies never echo submitted values" test; mutation checks; the races as automated tests.

**Measurements owed:** the login timing gap between a known and an unknown email; bcrypt cost 10 vs 12; the registration race.

**Accepted behaviour:** two quick resends can send two emails (only the newest link works); the fifth failure writes `ACCOUNT_LOCKED` a moment before its `LOGIN_FAILED`; `user_tokens` keeps revoked rows until Phase 9; resend and reset leak a little through timing until Phase 9's asynchronous sending.

**Scheduled elsewhere:** a prod `EmailSender`, asynchronous sending and the cleanup job (Phase 9); rate limiting against email bombing, lockout abuse and guessing (Phase 10); trusted forwarded headers (Phase 11); email change (backlog); profile (§2.6).

**Nits:** `RegisterRequest.toString()` prints the email and username (other request records hide them) · the slice tests mock the concrete `TaskflowUserDetailsService` instead of the interface · `UserAuthenticationChecks` doesn't say the expired checks were dropped on purpose · the 403 detail "Email Not Verified" could tell the user what to do · `@ValidPassword`'s message key has no `ValidationMessages.properties` entry · `changePassword` uses `catch (Exception e)` + `instanceof` · the `uk_user_tokens_active` name is written twice · `markEmailVerified` overwrites an earlier verification time · `verify`/`resend` could live in their own service · unused `setRole`/`setTimezone` in `UserAccount`.

---

## Changelog

| Date | Change |
|---|---|
| 2026-10-12 | Created from the Phase 1 requirements and learning log and the current code: principles, architecture, C1–C12, recipe, quick reference, verified framework behaviour, gaps. Each entry notes what Phase 2 changed (Basic removed, bearer principal, login tokens and cookie, session revocation on reset and change, `UserRole` and IP parsing moved, V6 event types). |
