# Phase 2 — Implementation Reference (JWT, sessions, refresh)

> **What this is:** a reusable record of *what* Phase 2 built, *how* it's built, and the *rules* it follows, written so the same design can be rebuilt in another Spring Boot project without re-deriving it. Read Part D first if you're implementing it somewhere new.
> **What it isn't:** the theory (that's `JWT_AND_SESSIONS_GUIDE.md`), the requirements and decisions with their alternatives (`PHASE_2_REQUIREMENTS.md`), or what was learned and measured (`PHASE_2_LEARNING_LOG.md`).
> **Kept current:** updated at the end of **every Phase 2 sub-section**. The coverage table and the changelog at the bottom say what's in.

| Sub-section | Feature | Status in this document |
|---|---|---|
| §2.1 | Keys, access tokens, sessions + refresh tokens, login with a cookie | ✅ covered (code committed: `9b10252`) |
| §2.2 | Bearer authentication on every request, JSON 401/403, Basic removed | ✅ covered (code committed: `9e07cbb`) |
| §2.3 | Refresh with rotation and reuse detection, `Origin` check | ✅ covered (code committed: `34c15f2`; run passed 2026-10-11; tests skipped, D14) |
| §2.4 | Logout, log out everywhere, session list, revocation on password events | ✅ covered (run passed in full 2026-10-12 on a scratch copy, incl. deliberate failures 1–4; committed `b6f3dde`; tests not written, D16) |
| §2.5 | CORS | ✅ covered (run passed 2026-10-12, incl. deliberate failures 1–3; committed `b6f3dde`; tests not written, D16) |
| §2.6 | Profile (carried from §1.7) | ⏭️ moved to Phase 3 (D17) |

**Stack this was built on:** Java 21 · Spring Boot 3.5.16 · Spring Security 6.5.11 (resource server + Nimbus JOSE) · Hibernate 6.6 · PostgreSQL 17 · Flyway.

---

## Contents

- [Part A — Design principles and rules](#part-a--design-principles-and-rules)
- [Part B — The architecture at a glance](#part-b--the-architecture-at-a-glance)
- [Part C — Each feature: what, how, rules, pitfalls](#part-c--each-feature-what-how-rules-pitfalls)
  - [C1. Signing keys and JWT configuration (§2.1)](#c1-signing-keys-and-jwt-configuration-21)
  - [C2. Issuing access tokens (§2.1)](#c2-issuing-access-tokens-21)
  - [C3. Sessions and refresh tokens: schema, entities, repositories (§2.1)](#c3-sessions-and-refresh-tokens-schema-entities-repositories-21)
  - [C4. Login: starting a session, the cookie (§2.1)](#c4-login-starting-a-session-the-cookie-21)
  - [C5. Verifying bearer tokens on every request (§2.2)](#c5-verifying-bearer-tokens-on-every-request-22)
  - [C6. The request principal and its readers (§2.2)](#c6-the-request-principal-and-its-readers-22)
  - [C7. One error format for the filter chain (§2.2)](#c7-one-error-format-for-the-filter-chain-22)
  - [C8. Refresh: rotation and reuse detection (§2.3)](#c8-refresh-rotation-and-reuse-detection-23)
  - [C9. The `Origin` check on cookie endpoints (§2.3)](#c9-the-origin-check-on-cookie-endpoints-23)
  - [C10. Security events added by Phase 2 (§2.3)](#c10-security-events-added-by-phase-2-23)
  - [C11. Logout, the session list, revoke one, log out everywhere (§2.4)](#c11-logout-the-session-list-revoke-one-log-out-everywhere-24)
  - [C12. Revocation on password reset and change (§2.4)](#c12-revocation-on-password-reset-and-change-24)
  - [C13. CORS (§2.5)](#c13-cors-25)
- [Part D — Rebuilding this in another project: the recipe](#part-d--rebuilding-this-in-another-project-the-recipe)
- [Part E — Quick reference: endpoints, errors, configuration](#part-e--quick-reference-endpoints-errors-configuration)
- [Part F — Framework behaviour we relied on (checked in the sources)](#part-f--framework-behaviour-we-relied-on-checked-in-the-sources)
- [Part G — Known gaps, debt and what comes next](#part-g--known-gaps-debt-and-what-comes-next)
- [Changelog](#changelog)

---

## Part A — Design principles and rules

These are the rules every Phase 2 class follows. Each one says **what** the rule is and **why**, because a rule without its reason gets "simplified" away.

### A1. Judge every design by what happens to the attacker's copy

Deleting a token on the client logs out the honest user and nobody else. Every scenario that matters (a stolen laptop, an XSS, a token in a log line) is one where **someone else** holds a copy. So "logout", "log out everywhere" and "password reset" must kill credentials **on the server**, and take effect on the attacker's next request, not when the token expires.

### A2. Two credentials with two jobs

- **Access token:** a short-lived (15 min) signed JWT, sent in the `Authorization` header on every API call, verified without a database lookup for its signature and expiry.
- **Refresh token:** a long-lived (14 days idle, 30 days absolute) **opaque random string**, stored server-side **only as a hash**, sent only to the auth endpoints in an `HttpOnly` cookie, checked against the database every time.

The access token is cheap to verify; the refresh token is always revocable. Neither one has to be both.

### A3. Revocation is one row: the session

A login creates one `user_sessions` row: the **family** every refresh token of that login belongs to. Every access token carries that row's id as the `sid` claim, and every request checks that the session is still live (one primary-key lookup). Revoking the session row therefore kills, in one write:

- every refresh token of that login (they belong to it), **and**
- every access token of that login (they carry its `sid`), on their next request.

No token deny-list, no `iat` cut-off, no per-token revocation column.

### A4. Fail closed, at startup

Missing or inconsistent configuration stops the application before it serves a request, with a message naming the property: no signing key outside dev/test, a mismatched key pair, a blank issuer, a negative lifetime, `SameSite=None` without `Secure`, an origin with a trailing slash. A security bug that shows up as "the app won't start" costs minutes; one that shows up in production costs an incident.

### A5. Nothing secret in claims, logs or `toString()`

- A JWT is **signed, not encrypted**: anyone holding it can read the payload. Claims carry ids and a role, never an email or anything personal.
- Every type that holds a token or a key overrides `toString()` to mask it (Java records print every component by default). This applies to properties, the signing key, issued tokens, every result type, and every response body.
- Logs carry ids and enum reasons, never token values.

### A6. Decide with the database, not with a value Java read earlier

Every state change after insert is a **conditional `UPDATE`** whose row count is the decision: "consume this token *if it's still unconsumed and unexpired*", "touch this session *if it's still live*", "revoke *if not already revoked*". PostgreSQL makes concurrent updates of one row wait and re-check, so exactly one request wins. Check-then-act ("load it, check a field, set it, save") loses races; a partial unique index (`uk_refresh_tokens_active`) backs the code up in the database.

### A7. Session entities are insert-only from Java

`UserSession` and `RefreshToken` have no setters, and every column is `updatable = false`. Hibernate writes **every column** of a dirty entity, so a session loaded before a concurrent "log out everywhere" would write `revoked_at = NULL` back and resurrect it. With insert-only entities, all changes go through explicit conditional bulk updates, which `updatable = false` doesn't affect.

### A8. One clock reading per operation, truncated to storage precision

A login or refresh reads the injected `Clock` once and passes that instant down: the session's timestamps, the refresh token's timestamps and the access token's `iat` are the same instant. Instants are truncated to **microseconds** before they're stored (PostgreSQL's precision), and JWT times to **seconds** (the JWT format's precision), so in-memory values equal what's stored or signed.

### A9. Transaction boundaries are deliberate

- **Login is not transactional** (Phase 1): a failed authentication must not roll back the failure counter or the `LOGIN_FAILED` event.
- **Session start is one transaction:** the session row and its first refresh token commit together.
- **Refresh is one transaction that returns a result instead of throwing.** Its reuse branch revokes the session and records an event; an exception would roll both back. Rejections that must *not* persist anything (a dead session) mark the transaction rollback-only and still return.
- **Events recording a state change join that change's transaction** (`MANDATORY`), so the event exists if and only if the change happened.
- **Workflows** (`SessionWorkflow`, `LoginService`) sit outside transactions and turn committed decisions into HTTP responses.

### A10. `common` never imports a feature

Shared security code (`common/security`) takes ids and strings, and defines interfaces that features implement: `SessionStatus` is an interface in `common`, implemented in `user.session`. The role enum moved to `common/security` so the token converter can validate roles without importing `user`.

### A11. One error format everywhere

Every error, whether raised in a controller or in the security filter chain before Spring MVC runs, is the same RFC 7807 `ProblemDetail` with `code`, `instance`, `timestamp` and `correlationId`, built by one factory (`ProblemDetails`). Details are fixed sentences per code, never exception messages (JWT libraries' messages describe the token).

### A12. No oracle for attackers

Every refresh failure is the same `401 INVALID_REFRESH_TOKEN`, whatever the internal reason; the reason goes to the debug log and to tests. Telling a thief "that token was rotated 3 seconds ago" would help them.

### A13. Verify framework behaviour in the source, not from memory

Every claim about Spring Security, Hibernate or Boot that a design depends on was checked in the `-sources.jar` files in `~/.m2` (Part F lists them). Several "obvious" assumptions turned out false (a token without `exp` passes validation; a custom validator replaces expiry checking; an invalid token is rejected even on `permitAll` paths).

---

## Part B — The architecture at a glance

### B1. Where each piece lives

```
common/security                                   user/session                         user
───────────────                                   ────────────                         ────
JwtProperties, CorsProperties                     SessionProperties                    LoginService (+ LoginResult, LoginResponse)
JwtKeyConfig ─► JwtSigningKey, JwtEncoder,        UserSession, RefreshToken            SessionWorkflow (+ RefreshOutcome, RefreshResponse)
                JwtDecoder                        UserSessionRepository                AuthController (/login, /refresh, /logout)
AccessTokenIssuer, IssuedAccessToken,             RefreshTokenRepository               SessionController (/users/me/sessions)
  TaskflowClaims                                  SessionService (start, refresh,      RefreshCookie
                                                    revoke…, listLive)                 PasswordWorkflow, PasswordService,
SessionJwtConverter ─► SessionStatus (interface)  JpaSessionStatus (implements it)       UserController, AuthenticationEventsListener
AuthenticatedUser, TaskflowAuthenticationToken    StartedSession, RefreshResult, SessionResponse,
TaskflowBearerTokenResolver, AllowedOrigins         AccountClaims, SessionSummary
ProblemAuthenticationEntryPoint,                  SessionRevokeReason, SessionErrorCode
  ProblemAccessDeniedHandler, ProblemResponseWriter
TaskflowSecurityConfig, UserRole                  user/event: SecurityEventType, SecurityEvent,
common/error: ProblemDetails,                       SecurityEventRecorder (3 new events)
  RequestForbiddenException, CommonErrorCode
```

### B2. The four flows

**Login** (`POST /api/v1/auth/login`, §2.1)
```
authenticate(email, password)          Phase 1 rules unchanged: 401 / 403 / failure events
now = clock.instant()                  one reading
SessionService.start(id, client, now)  own transaction: user_sessions row + first refresh_tokens row (hash only)
record LOGIN_SUCCEEDED                 after the session, so a failed insert can't leave "succeeded"
AccessTokenIssuer.issue(…, sid, now)   current username + role, from the loaded account
← 200 {accessToken, tokenType, expiresIn, user} + Set-Cookie: refresh token (HttpOnly, Secure, SameSite=Strict, Path=/api/v1/auth)
```

**Every API request** (§2.2)
```
CorrelationIdFilter → … → CorsFilter (preflight answered here; disallowed origin → 403) → BearerTokenAuthenticationFilter
  resolver: "Bearer <token>" from the header (nothing under /api/v1/auth/**)
  JwtDecoder: signature (RS256 only) → validators (exp/nbf with Clock + 5 s skew, exp present, iss, aud)
  SessionJwtConverter: sub, sid, preferred_username, role → SessionStatus.isLive(sid, sub) → TaskflowAuthenticationToken
→ AuthorizationFilter (rules) → controller
failure → ProblemAuthenticationEntryPoint (401 / 400)   denied → ProblemAccessDeniedHandler (403)
```

**Refresh** (`POST /api/v1/auth/refresh`, §2.3)
```
SessionWorkflow: Origin allowed? (else 403, nothing read) → now = clock.instant()
SessionService.refresh (one transaction, returns a result):
  1 shape check → 2 find by hash (+ session) → 3 conditional consume
    0 rows → 4 expired? / consumed within grace (superseded)? / else reuse: revoke session + REFRESH_TOKEN_REUSED
  5 conditional touch of the session → 0 rows: rollback-only, SESSION_ENDED
  read current username + role → insert successor (expiry capped at the session's end)
Workflow: sign the access token → controller: 200 + new cookie | 401 (+ clearing cookie unless superseded)
```

**Revocation** (§2.4; also reuse detection in §2.3)
```
POST /auth/logout (cookie + Origin)        → the cookie's session            LOGOUT            no event
DELETE /users/me/sessions/{id} (bearer)    → that session, if yours and live REVOKED_BY_USER   SESSION_REVOKED
DELETE /users/me/sessions (bearer)         → all your sessions, this one too LOGOUT_ALL        ALL_SESSIONS_REVOKED
password reset (inside its transaction)    → all your sessions               PASSWORD_RESET    (PASSWORD_RESET already)
password change (inside its transaction)   → all except the current sid      PASSWORD_CHANGED  (PASSWORD_CHANGED already)
each one: UPDATE user_sessions SET revoked_at = now, revoke_reason = ? WHERE … AND revoked_at IS NULL
→ refresh tokens of that session stop working (step 5) → access tokens stop working (SessionStatus)
```

### B3. The schema (V5, plus V6's constraint change)

| Table | One row per | Key columns | Constraints and indexes that matter |
|---|---|---|---|
| `user_sessions` | login (the family) | `user_account_id`, `created_at`, `expires_at` (absolute, login + 30 d), `last_refreshed_at`, `revoked_at`, `revoke_reason`, `ip_address` (`inet`), `user_agent` | FK to `user_accounts` `ON DELETE CASCADE`; `expires_at > created_at`; `last_refreshed_at >= created_at`; `revoke_reason` in the six reasons; **reason present if and only if revoked**; index on `user_account_id` |
| `refresh_tokens` | issued refresh token (kept after use) | `session_id`, `token_hash` (SHA-256 hex), `created_at`, `expires_at` (idle, capped), `consumed_at` | FK to `user_sessions` `ON DELETE CASCADE`; unique `token_hash`; hash format `^[0-9a-f]{64}$` (a raw token can never be stored); `expires_at > created_at`; **partial unique index: one unconsumed token per session** |
| `security_events` (Phase 1) | security event | V6 adds `SESSION_REVOKED`, `ALL_SESSIONS_REVOKED`, `REFRESH_TOKEN_REUSED` | Event-type check replaced forward-only (applied migrations are frozen) |

There's no `revoked_at` on refresh tokens on purpose: revocation lives on the session, in one place.

---

## Part C — Each feature: what, how, rules, pitfalls

### C1. Signing keys and JWT configuration (§2.1)

**What it does.** Provides one RSA key pair for signing and verifying access tokens, and the typed, validated configuration around it.

**How it's built.**
- `JwtProperties` (`taskflow.security.jwt`): `issuer`, `audience` (non-blank), `access-token-ttl` (positive), `clock-skew` (not negative), and `key-id` / `private-key` / `public-key` as PEM text. The three key values are **all or none**; blanks count as "not set". `toString()` redacts the private key.
- `JwtKeyConfig` decides at startup:
  - **key configured** → parse the PEM text (PKCS#8 private, X.509 public, via Spring Security's `RsaKeyConverters`), check the public key's **modulus equals** the private key's (an RSA pair shares it), and check **≥ 2048 bits**;
  - **no key, profile `dev` or `test`** → generate a 2048-bit pair with an `ephemeral-<uuid>` key id and log one `WARN` (the key id only, never key material);
  - **no key, any other profile** → refuse to start, naming the three properties and their environment variables.
- `JwtSigningKey` (key id + public + private) is a **single bean** shared by the encoder and the decoder, with a redacted `toString()`.
- The encoder is a `NimbusJwtEncoder` over a one-key `JWKSet`.

**Rules.**
- RS256, not HS256: with a shared secret, anything that can verify can also mint tokens. Other services can be given the public key safely.
- `kid` from day one, so keys can be rotated: sign with the new key, verify both for one access lifetime, drop the old one. Because refresh tokens are opaque database rows, a rotation logs nobody out; a token signed with a removed key gets 401 and the client refreshes.
- An **allowlist** of profiles for the throwaway key (`dev`, `test`), not a blocklist (`prod`): a misspelt or new profile must fail, not quietly get a throwaway key.
- Keys come from environment variables (relaxed binding: `TASKFLOW_SECURITY_JWT_PRIVATE_KEY`) or mounted secret files (`spring.config.import: optional:configtree:/run/secrets/`), never from YAML or the repository.

**Pitfalls.**
- Generating or loading the key separately for the encoder and the decoder gives two different pairs in dev: every token signs and every verification fails. One bean.
- Nimbus's `RSAKey.toString()` prints the private key as JSON; never log it or make it a bean.
- A throwaway key is per process: a restart (or a second instance) invalidates every access token.
- `RsaKeyConverters` expects multi-line PEM; a single-line value with escaped `\n` fails (Phase 11).

**Files.** `common/security/JwtProperties`, `JwtKeyConfig`, `JwtSigningKey`; `application.yml` (`taskflow.security.jwt`).

---

### C2. Issuing access tokens (§2.1)

**What it does.** Signs a 15-minute access token for a user, a role and a session.

**How it's built.** `AccessTokenIssuer.issue(userId, username, role, sessionId, now)`:
1. `iat` = `now` truncated to whole seconds; `exp` = `iat` + the access lifetime.
2. Header: `alg RS256`, `kid` = the signing key's id, `typ JWT`.
3. Claims, exactly these and nothing else:

| Claim | Value | Why it's there |
|---|---|---|
| `iss` | `taskflow` | Tokens from another issuer are rejected |
| `aud` | `taskflow-api` | Tokens minted for another audience are rejected |
| `sub` | user id (string) | Stable and not personal data; ids never change |
| `sid` | session id (string) | The per-request liveness check (A3) |
| `preferred_username` | app username | The auditor writes it to `created_by` without a lookup (standard OIDC name) |
| `role` | `USER` / `ADMIN` | Authorities without a lookup; stale for at most 15 min |
| `iat`, `exp` | issue time, + 15 min | Expiry, checked by the decoder |

4. Returns `IssuedAccessToken(value, issuedAt, expiresAt)` with `expiresInSeconds()` and a masked `toString()`.

Claim names live in `TaskflowClaims`, shared by the issuer and the converter, so they can't drift apart.

**Rules.**
- "Now" is a parameter, not read inside: the caller's single clock reading (A8).
- Role and ids as strings (`common` can't import the feature's enum; JWT and OIDC define `sub`/`sid` as strings; strings avoid JavaScript losing precision on 64-bit ids).
- `expiresIn` in responses is **seconds**.

**Pitfalls.**
- Not truncating `iat` makes the returned expiry disagree with the token's `exp` by up to a second.
- A one-element audience is serialised as a plain string, not an array; both are valid, and `Jwt.getAudience()` returns a list either way.
- Testing token *content* through a full decoder fails once the real date passes the fixed test clock's expiry; parse with Nimbus `SignedJWT` and verify the signature directly instead.

**Files.** `common/security/AccessTokenIssuer`, `IssuedAccessToken`, `TaskflowClaims`.

---

### C3. Sessions and refresh tokens: schema, entities, repositories (§2.1)

**What it does.** Stores logins (sessions) and their refresh tokens so that both can be checked and revoked.

**How it's built.**
- **Migration V5** (Part B3).
- **`SessionProperties`** (`taskflow.security.sessions`): `refresh-token-idle-ttl` 14 d, `absolute-ttl` 30 d (both positive, no defaults), `reuse-grace` 10 s (zero allowed = strict detection), and the cookie settings `name`, `path` (must start with `/`), `secure` (**defaults to `true`**), `same-site` (Spring Boot's `SameSite` enum, so a typo fails at startup; `NONE` requires `secure`).
- **Entities** extend `IdentifiedEntity` (not `BaseEntity`: login and refresh are anonymous, so `created_by` would always say `system`):
  - `UserSession.start(accountId, clientInfo, now, absoluteTtl)`: one clock reading sets `created_at`, `last_refreshed_at` and `expires_at`. Stores the account **id**, not an association (every operation works by id).
  - `RefreshToken.issue(session, hash, now, expiresAt)`: asserts the expiry doesn't outlive the session. `LAZY` many-to-one to the session; no collection on the session side.
  - Both: no setters, **every column `updatable = false`** (A7), timestamps truncated to microseconds, hand-written `toString()` without hash, IP or user agent.
- **Repositories**, every change a conditional `@Modifying` query returning a row count:

| Method | Condition in the `WHERE` | Used by |
|---|---|---|
| `RefreshTokenRepository.consume(id, now)` | not consumed, not expired | refresh step 3 |
| `findWithSessionByTokenHash(hash)` | join-fetches the session | refresh step 2 |
| `findConsumedAtById(id)` | scalar query (reads the committed row) | refresh step 4 |
| `findSessionIdByTokenHash(hash)` | id only, no entity | logout (§2.4) |
| `UserSessionRepository.isLive(sid, accountId, now)` | belongs to the account, not revoked, not expired; returns a boolean | every request |
| `touch(sid, now)` | not revoked, not expired | refresh step 5 |
| `revoke(sid, reason, now)` | not revoked, not expired | reuse, logout |
| `revokeOwned(sid, accountId, reason, now)` | + belongs to the account (the IDOR guard) | revoke one (§2.4) |
| `revokeAll` / `revokeAllExcept` | the account's unrevoked sessions | §2.4 |
| `findLive(accountId, now, pageable)` | record projection, fixed order | session list (§2.4) |
| `findAccountClaims(accountId)` | current username + role, projection | refresh |

**Rules.**
- Only the **SHA-256 hash** of a refresh token is stored (32 random bytes need no slow hash; a deterministic hash is needed for lookup).
- Consumed tokens are **kept** until their session ends: deleting them would make a replayed stolen token look "unknown" and theft would go undetected.
- Every revoke requires "not revoked yet", so a second revoke changes 0 rows and the **original reason is kept**.

**Pitfalls.**
- After a bulk update, entities loaded earlier in the same transaction still show old values. Read only fields that never change (`id`, `userAccountId`, `expiresAt`, `createdAt`). Don't add `clearAutomatically = true`: it would detach entities the method still uses.
- `id <> NULL` is *unknown* in SQL: `revokeAllExcept(account, null, …)` would revoke **nothing**. Assert the excepted id is non-null (§2.4).
- `PageableFactory` always adds a sort; pass an unsorted `PageRequest` to `findLive`, whose order is in the query.
- `ddl-auto: validate` doesn't check `updatable` or lengths; only the reasoning protects them.

**Files.** `db/migration/V5__create_user_sessions.sql`; `user/session/SessionProperties`, `UserSession`, `RefreshToken`, `SessionRevokeReason`, `UserSessionRepository`, `RefreshTokenRepository`, `SessionSummary`, `AccountClaims`.

---

### C4. Login: starting a session, the cookie (§2.1)

**What it does.** Turns a successful password check into a session, an access token in the body, and a refresh token in a cookie.

**How it's built.**
- `SessionService.start(accountId, clientInfo, now)` (`@Transactional`): saves the session, generates a raw token with Phase 1's `TokenCodec`, computes its expiry as **min(now + 14 d, session end)**, saves the hash, returns `StartedSession(sessionId, rawToken, maxAge)` with a masked `toString()`. The cookie's max age is computed from the stored token, so the cookie never outlives it.
- `LoginService.login` (not transactional): authenticate (Phase 1, unchanged) → one clock reading → `start` → record `LOGIN_SUCCEEDED` → load the account → issue the access token → `LoginResult`.
- `RefreshCookie` is the only class that knows the cookie: `set(raw, maxAge)`, `clear()` (same name, path and attributes, empty value, `Max-Age=0`), and `read(request)`.
- `AuthController.login` sets the cookie and returns `LoginResponse {accessToken, tokenType: "Bearer", expiresIn, user}`.

**Rules.**
- Cookie attributes: `HttpOnly` (XSS can't read it) · `Secure` · `SameSite=Strict` (not sent on cross-site requests) · `Path=/api/v1/auth` (only sent to the auth endpoints) · `Max-Age` (dies with the token).
- The access token is **not** a cookie: a header is never attached by the browser automatically, so the API needs no CSRF protection.
- The session is created **only after** authentication succeeds and **before** `LOGIN_SUCCEEDED` is recorded.
- `Cache-Control: no-store` comes from Spring Security's default headers; test it, don't code it.

**Pitfalls.**
- A clearing cookie with a different path leaves the real one in place: logout "succeeds" and the token stays. Set and clear come from one builder.
- `ResponseCookie.toString()` *is* the `Set-Cookie` header, raw token included: never log it.
- If recording the event fails after the session committed, the client gets a 500 and an unusable session expires on its own (accepted).

**Files.** `user/session/SessionService#start`, `StartedSession`; `user/LoginService`, `LoginResult`, `LoginResponse`, `RefreshCookie`, `AuthController#login`.

---

### C5. Verifying bearer tokens on every request (§2.2)

**What it does.** Authenticates every `/api/v1/**` request from `Authorization: Bearer <token>`, and refuses a token whose session was revoked on the very next request.

**How it's built.** Spring Security's resource server, with our own pieces plugged in:

```java
.oauth2ResourceServer(rs -> rs
        .bearerTokenResolver(bearerTokenResolver)
        .authenticationEntryPoint(entryPoint)
        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(new SessionJwtConverter(sessionStatus))))
.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
```

- **Decoder** (`JwtKeyConfig#jwtDecoder`): `NimbusJwtDecoder.withPublicKey(…)` pinned to RS256; validators added with `JwtValidators.createDefaultWithValidators(...)`:
  - `JwtTimestampValidator` with the configured skew (5 s) **and the injected `Clock`**;
  - a `JwtClaimValidator` requiring `exp` to be present;
  - `JwtIssuerValidator`, `JwtAudienceValidator`.
- **Converter** (`SessionJwtConverter`, a plain object, not a bean): parses `sub` and `sid` as numbers, requires `preferred_username`, parses `role` against the enum (unknown → rejected, never defaulted), then calls `SessionStatus.isLive(sid, sub)` **last**. Any failure → `InvalidBearerTokenException` (401).
- **`SessionStatus`** (interface in `common`) → `JpaSessionStatus` (in `user.session`): one boolean query; database errors propagate (an outage is a 500, never a 401).
- **Resolver** (`TaskflowBearerTokenResolver`): Spring's `DefaultBearerTokenResolver` (header only, strict `Bearer <token>` pattern, several tokens → 400), except it returns **no token under `/api/v1/auth/**`**.
- **Chain**: Basic removed; `permitAll` for the auth endpoints including `refresh` and `logout`; `denyAll()` last; stateless; CSRF off with the reason written next to it.

**Rules.**
- The order of checks is the cost order: signature → claims validators → claim parsing → database. Only genuine, unexpired, correctly addressed tokens ever cost a query.
- The session check lives in the **converter**, not in a validator: validators all run (no short-circuit), so a validator would query for expired tokens too.
- Nothing under `/api/v1/auth/` may ever need bearer authentication (the resolver ignores tokens there). That's why "log out everywhere" lives under `/users/me/sessions`.

**Pitfalls.**
- `setJwtValidator(x)` **replaces** the default validator, which is the only one checking `exp`: expiry silently off.
- `JwtTimestampValidator` uses `Clock.systemUTC()` and a 60 s skew unless told otherwise.
- A token with **no** `exp` passes the timestamp validator; require it explicitly.
- An invalid token is rejected **before** authorization, even on `permitAll` paths: without the resolver rule, a stale header breaks login and refresh.
- Making the converter a `Converter` bean adds it to Spring MVC's conversion service.
- Manual role changes stay stale for up to 15 min; revoke that user's sessions too.

**Files.** `common/security/JwtKeyConfig#jwtDecoder`, `SessionJwtConverter`, `SessionStatus`, `TaskflowBearerTokenResolver`, `TaskflowSecurityConfig`; `user/session/JpaSessionStatus`.

---

### C6. The request principal and its readers (§2.2)

**What it does.** Gives every component one small, typed answer to "who is calling".

**How it's built.**
- `AuthenticatedUser(id, username, role, sessionId)`: the principal on every bearer request. No email, no password hash, no status flags.
- `TaskflowAuthenticationToken`: one authority `ROLE_<role>`; `getName()` = username; `getCredentials()` = `null` (the raw token isn't kept); only the constructor can mark it authenticated.
- `TaskflowPrincipal` (Phase 1) stays, but **only while a password is being checked** (login, change-password).
- Readers updated: the auditor reads `AuthenticatedUser.username` (anything else → `system`); `UserController` uses `@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser`; `changePassword` loads the email by id (`findEmailById`) before re-authenticating; `UserRole` moved to `common/security`.

**Rules.**
- Spring's default `JwtAuthenticationToken` is not used: its principal is the raw `Jwt` (claims read by string everywhere), its authorities come from `scope` (`SCOPE_…`, so `hasRole` fails), its name is `sub`, and its credentials hold the token text.
- The lockout listener still reacts **only** to `TaskflowPrincipal`. A success event fires on **every** bearer request; reacting to it would write per request and let the owner's open tab reset the failure counter while an attacker guesses.
- Never add the email to the token to save a query.

**Pitfalls.**
- Everything that reads the principal follows its *type*. `errorOnInvalidType = true` fails loudly (500); the auditor fails **silently** (`system`). Only a `created_by` assertion catches the silent one.
- Spring Security Test's `jwt()` builds a `JwtAuthenticationToken`; tests that reach `@AuthenticationPrincipal AuthenticatedUser` or the auditor need `authentication(new TaskflowAuthenticationToken(...))`.

**Files.** `common/security/AuthenticatedUser`, `TaskflowAuthenticationToken`, `UserRole`; `common/config/AuditAwareImpl`; `user/UserController`, `PasswordWorkflow`, `UserAccountRepository#findEmailById`, `AuthenticationEventsListener`.

---

### C7. One error format for the filter chain (§2.2)

**What it does.** Makes the 401s and 403s produced before Spring MVC look exactly like every other API error.

**How it's built.**
- `ProblemDetails` (`common/error`): `create(code, status, detail, path)` and `enrich(problem, code, path)` add `code`, `timestamp`, `instance` and `correlationId`. `GlobalExceptionHandler` now uses `enrich` too, so there's one definition of an error body.
- `ProblemResponseWriter`: writes `application/problem+json` with Boot's `ObjectMapper` (which carries Spring's `ProblemDetail` mixin, so extra properties appear at the top level).
- `ProblemAuthenticationEntryPoint`: a presented-and-rejected token → `INVALID_ACCESS_TOKEN` with Spring's status (401, or 400 for several tokens) and `WWW-Authenticate: Bearer error="<code>"`; no token → `AUTHENTICATION_REQUIRED` 401 with `WWW-Authenticate: Bearer`.
- `ProblemAccessDeniedHandler`: `ACCESS_DENIED` 403.
- The **same entry point instance** is set in `oauth2ResourceServer` **and** in `exceptionHandling`.

**Rules.**
- `WWW-Authenticate` carries only the error code, not Spring's `error_description` (it contains the library's message).
- Writing the body directly avoids the `/error` dispatch Basic used to trigger.

**Pitfalls.**
- The bearer filter holds its **own** entry point; setting it only in `exceptionHandling` leaves rejected-token 401s with Spring's empty body.

**Files.** `common/error/ProblemDetails`, `CommonErrorCode`, `GlobalExceptionHandler`; `common/security/ProblemAuthenticationEntryPoint`, `ProblemAccessDeniedHandler`, `ProblemResponseWriter`.

---

### C8. Refresh: rotation and reuse detection (§2.3)

**What it does.** `POST /api/v1/auth/refresh` with the refresh cookie returns a new access token and **rotates** the cookie. Presenting an already-used refresh token is treated as theft: the session is revoked and the event recorded, except within 10 seconds of the use, which is the normal multi-tab race.

**How it's built.** `SessionService.refresh(rawToken, clientInfo, now)`, one `@Transactional` method that **returns** a `RefreshResult` (`Refreshed` or `Rejected(reason)`):

| Step | What | Outcome |
|---|---|---|
| 1 | Shape check (`TokenCodec.isWellFormed`): 43 base64url chars; a missing cookie is `null` | `MALFORMED`, no query |
| 2 | Find by hash, with its session | `UNKNOWN` if absent |
| 3 | **Conditional consume**: not consumed, not expired | 1 row → continue; 0 rows → step 4 |
| 4a | Expired (`expires_at` never changes, so the loaded entity is safe) | `EXPIRED` (not theft) |
| 4b | Re-read `consumed_at` with a fresh scalar query; consumed less than `reuse-grace` ago | `SUPERSEDED`: nothing revoked, **cookie not cleared** |
| 4c | Consumed earlier than that | `REUSED`: revoke the session (`REFRESH_TOKEN_REUSE`) + record `REFRESH_TOKEN_REUSED`, both committed |
| 5 | **Conditional touch** of the session: not revoked, not expired | 0 rows → **rollback-only**, `SESSION_ENDED` |
| — | Read the account's **current** username and role (projection) | missing → rollback-only, `SESSION_ENDED` |
| 6 | Insert the successor: expiry = min(now + 14 d, session end) | `Refreshed(sessionId, accountId, username, role, rawSuccessor, maxAge)` |

Around it:
- `SessionWorkflow.refresh` (not transactional): `Origin` check → one clock reading → `refresh` → on success, sign the access token with the same `sid` and the current username and role → `RefreshOutcome` (`Granted` or `Denied(clearCookie)`).
- `AuthController.refresh` builds the response itself: 200 + `RefreshResponse {accessToken, tokenType, expiresIn}` + the new cookie, or 401 `INVALID_REFRESH_TOKEN` (`ProblemDetails.create`) with a clearing cookie **unless superseded**. It's built in the controller because a rejection needs a `Set-Cookie` header an exception handler wouldn't add.

**Rules.**
- **Never throw for an expected rejection inside the transaction.** The reuse branch's revocation and event must commit with the 401.
- **Undo the consume when the session turns out to be dead** (`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`, then return). Otherwise a client that retries later (ignoring the clearing cookie, or after a lost response) presents a consumed token and triggers a false `REFRESH_TOKEN_REUSED`. Rolling back also covers the session being revoked between lookup and consume. Consequence: `refresh` must be called **outside** any transaction.
- **Expired is not reused.** Only a *consumed* token signals theft; an expired one is a user who came back late.
- **The grace window is measured from `consumed_at`** (when the winner rotated), not from creation.
- **Superseded never clears the cookie**: the browser already holds the winner's new token.
- **Reuse revokes the session, not the user**: it kills exactly the leaked family. It's recorded even if the session had already ended.
- **Refresh is allowed while the account is locked**: a lock stops password guessing; existing sessions are the owner's.
- **The current username and role are read inside the transaction**, so a missing account rejects without rotating (instead of a 500 after the rotation committed, which would make the next refresh look like reuse).
- One error code for every failure (A12).

**Why exactly one of N concurrent refreshes wins.** All N load the token (each sees "unconsumed"). All N run the conditional consume; PostgreSQL locks the row for the first and makes the others wait. After the first commits, each waiting `UPDATE` re-checks `consumed_at IS NULL`, finds it false, and changes 0 rows. Their entities still say "unconsumed", which is why step 4b re-reads the committed value. They see it consumed milliseconds ago: superseded. Verified in a scratch run: 20 parallel refreshes → 1 × 200, 19 × 401, no 500.

**Pitfalls.**
- Throwing from the reuse branch: the 401 is right, but the session stays live and no event exists.
- Trusting the entity's `consumedAt` after losing the race: `null` for a consumed token.
- Updating the session through the entity: resurrects a concurrently revoked session.
- Clearing the cookie on superseded: the loser deletes the winner's cookie.
- Refresh tokens accumulate (one row per rotation, kept until the session ends); Phase 9's cleanup job removes them with expired sessions.

**Files.** `user/session/SessionService#refresh`, `RefreshResult`, `AccountClaims`, `SessionErrorCode`; `user/SessionWorkflow`, `RefreshOutcome`, `RefreshResponse`, `RefreshCookie#read`, `AuthController#refresh`.

---

### C9. The `Origin` check on cookie endpoints (§2.3)

**What it does.** Refuses a refresh (and, in §2.4, a logout) started by a browser page on an origin TaskFlow doesn't serve: `403 ORIGIN_NOT_ALLOWED`, before the cookie is read, so the user's token isn't consumed.

**How it's built.**
- `CorsProperties` (`taskflow.security.cors.allowed-origins`): a non-empty list of exact origins, each validated as `scheme://host[:port]` (no wildcard, path or trailing slash). Pulled forward from §2.5, which will reuse it for CORS. Dev and test: `http://localhost:3000`; prod: `${CORS_ALLOWED_ORIGINS}`.
- `AllowedOrigins.permits(originHeader)`: **no header → allowed**; a present header must match an entry exactly.
- `SessionWorkflow` calls it first and throws `RequestForbiddenException` (no transaction has started).

**Rules.**
- `SameSite=Strict` stops other **sites**, but `app.example.com` and `evil.example.com` are the same site. Browsers always send `Origin` on a cross-origin `POST` and pages can't forge it, so checking it closes that gap.
- A missing `Origin` is allowed: curl, mobile apps and server clients don't send it, and CSRF needs a browser, which always does. Rejecting it breaks clients and stops nothing.
- Exact string comparison only (no `startsWith`); the literal `null` sent by sandboxed pages is refused.
- No CSRF tokens: for two endpoints that `SameSite` plus `Origin` already protect, they'd add a second token for the client to fetch and echo.
- **Since §2.5, the CORS filter answers first** (C13): a *cross-origin* request from a disallowed origin is rejected by Spring's `CorsFilter` with a plain-text `403 Invalid CORS request` before it reaches `AllowedOrigins`. `AllowedOrigins` stays as the second layer: it still refuses a same-origin request whose `Origin` isn't listed (the CORS filter ignores same-origin requests), and it keeps the cookie endpoints protected if the CORS configuration is ever removed or loosened.

**Files.** `common/security/CorsProperties`, `AllowedOrigins`; `common/error/RequestForbiddenException`; `user/SessionWorkflow`; `application-{dev,prod}.yml`, `src/test/resources/application-test.yml`.

---

### C10. Security events added by Phase 2 (§2.3)

**What it does.** Records three new kinds of security event in the append-only `security_events` log.

**How it's built.** V6 replaces the event-type check (forward only). `SecurityEventType` gains `SESSION_REVOKED`, `ALL_SESSIONS_REVOKED` and `REFRESH_TOKEN_REUSED`; each has its own factory on `SecurityEvent` (no failure reason) and its own recorder method with **`Propagation.MANDATORY`**.

**Rules.**
- Record what's security-relevant or an attack signal; don't record a plain logout or a routine refresh (the session row already says it, and history would drown in refreshes).
- `MANDATORY`, not `REQUIRED`: the event must commit with the revocation it describes, and a call from outside a transaction must fail loudly rather than commit alone.
- A reuse event records the **presenter's** IP and user agent, often the attacker's.

**Files.** `db/migration/V6__add_session_security_events.sql`; `user/event/SecurityEventType`, `SecurityEvent`, `SecurityEventRecorder`.

---

### C11. Logout, the session list, revoke one, log out everywhere (§2.4)

**What it does.** Lets a user end **this** session, **one** chosen session, or **all** of them, and see which sessions exist. Every one takes effect on the next request, because every access token is checked against its session (A3).

**How it's built.**

| Action | Endpoint | Authenticated by | `SessionService` method | Revokes | Event | Cookie |
|---|---|---|---|---|---|---|
| Log out this device | `POST /api/v1/auth/logout` | the refresh **cookie** + `Origin` check | `revokeByRefreshToken(raw, now)`: shape check → `findSessionIdByTokenHash` (an id, no entity) → `revoke(sid, LOGOUT)` | the cookie's session, whatever the token's state | none | always cleared |
| List my sessions | `GET /api/v1/users/me/sessions?page&size` | bearer | `listLive(accountId, currentSid, pageable, now)` → `findLive` projection → `SessionResponse` with `current` | — | — | — |
| End one session | `DELETE /api/v1/users/me/sessions/{id}` | bearer | `revokeOne(accountId, sid, client, now)` → `revokeOwned` (owner in the `WHERE`) → event | that session, if yours and live | `SESSION_REVOKED` | cleared if it's the current one |
| Log out everywhere | `DELETE /api/v1/users/me/sessions` | bearer | `logOutEverywhere(accountId, client, now)` → `revokeAll(LOGOUT_ALL)` → event once | all your live sessions, **this one included** | `ALL_SESSIONS_REVOKED` | always cleared |

- `SessionWorkflow` (not transactional) reads the clock once per call, applies the `Origin` check to logout, and turns "nothing revoked" into `404 SESSION_NOT_FOUND` **after** `revokeOne`'s transaction (which wrote nothing) is over.
- `SessionController` (`/api/v1/users/me/sessions`) takes the owner from `@AuthenticationPrincipal AuthenticatedUser`; no endpoint takes an account id.
- `SessionResponse {id, createdAt, lastRefreshedAt, expiresAt, ipAddress, userAgent, current}`: the IP as a plain literal (`getHostAddress()`); `toString()` hides IP and user agent.
- The list's order (`lastRefreshedAt desc, id desc`) is `SessionService.LIST_SORT`, passed through `PageableFactory` like every other list; the query itself has no `ORDER BY` (the factory always adds a sort, so an `ORDER BY` in the query would be applied twice).

**Rules.**
- **Logout reads the cookie, not the bearer token**, so it works after 15 idle minutes without a refresh first, and it lives under `/api/v1/auth/**` like refresh.
- **Logout is always 204 and always clears the cookie**: idempotent for a double-click, a dead session, a garbage or missing cookie. Only a disallowed `Origin` fails (403), before anything is read or cleared.
- **The owner is in the `WHERE` clause** (`id = ? AND user_account_id = ?`): someone else's session id matches no row. It's a **404 identical to a random id's**, never a 403, because session ids come from a guessable global sequence (IDOR by construction).
- **Session operations live under `/users/me/sessions`, not `/auth`**: they need the bearer token, which §2.2's resolver ignores under `/auth`.
- **Clearing the cookie from `/users/me/...` names the cookie's own `Path` (`/api/v1/auth`)**: a response may set a cookie for any path, and the browser only deletes a cookie whose name and path match.
- **"Log out everywhere" includes this device**: it's the panic button; a user who presses it suspects compromise.
- **No event for a plain logout**; events for revoking from the list and for log-out-everywhere (security-relevant), in the same transaction (`MANDATORY` recorder methods).
- No Spring `.logout()` / `LogoutFilter`: built for server sessions, it redirects (302) to `/login?logout` and runs before the rules.

**Pitfalls.**
- Looking the session up by id and checking the owner in Java: a later refactor forgets the check (deliberate failure 1).
- A clearing cookie with another `Path` leaves the real one in the browser (deliberate failure 3).
- `DELETE /users/me/sessions/` (trailing slash) must not reach "delete all": Spring Framework 6 doesn't match trailing slashes, so it's a 404 and revokes nothing *(seen in a scratch run)*. Keep it that way; test it.

**Files.** `user/session/SessionService` (`revokeByRefreshToken`, `revokeOne`, `logOutEverywhere`, `listLive`, `LIST_SORT`), `SessionResponse`, `SessionErrorCode#SESSION_NOT_FOUND`, `UserSessionRepository#findLive`; `user/SessionWorkflow`, `SessionController`, `AuthController#logout`.

---

### C12. Revocation on password reset and change (§2.4)

**What it does.** A password **reset** ends every session of the account; a password **change** ends every session except the one that made it. Lockout ends nothing.

**How it's built.**
- `SessionService.revokeAll(accountId, reason, now)` and `revokeAllExcept(accountId, exceptSid, reason, now)`, both `@Transactional(propagation = MANDATORY)`.
- `PasswordService.confirmReset` calls `revokeAll(…, PASSWORD_RESET, now)`; `PasswordService.applyChange(accountId, currentSessionId, …)` calls `revokeAllExcept(…, currentSessionId, PASSWORD_CHANGED, now)`. Both run **inside** the password write's transaction, with that method's own `now`.
- `PasswordWorkflow.changePassword` passes `AuthenticatedUser.sessionId()` as the current session.

**Rules.**
- **Revocation commits with the password change, or not at all.** A separate step afterwards could fail and leave "password changed, attacker still logged in" (deliberate failure 2). `MANDATORY` makes a call outside a transaction throw instead of committing alone.
- **Reset → all sessions** (account-takeover recovery); **change → all others** (the caller just proved the password; no pointless re-login); **lockout → none** (otherwise five bad guesses by anyone would log the owner out everywhere) ↺ D7.
- **`revokeAllExcept` refuses a null session id** (`Assert.notNull`): `id <> NULL` is *unknown* in SQL, so the `WHERE` would match nothing and **no** session would be revoked, silently (deliberate failure 4).
- The bulk `UPDATE` touches `user_sessions` only, so the `UserAccount` changed earlier in the transaction is never re-saved over it.
- No new event: `PASSWORD_RESET` / `PASSWORD_CHANGED` are already recorded by the same transaction.

**Pitfalls.**
- Calling `revokeAll` through `this` inside `SessionService` skips the proxy and its `MANDATORY` check (`logOutEverywhere` therefore calls the repository directly).

**Files.** `user/session/SessionService` (`revokeAll`, `revokeAllExcept`); `user/PasswordService` (`confirmReset`, `applyChange`); `user/PasswordWorkflow#changePassword`.

---

### C13. CORS (§2.5)

**What it does.** Lets a browser frontend on an allowed origin (dev: `http://localhost:3000`) call the API from JavaScript and **read** the responses. Credentials (the refresh cookie) are allowed only on `/api/v1/auth/**`; scripts on any other origin can't read responses.

**How it's built.**
- `CorsProperties` (from §2.3): exact origins, validated at startup (non-empty; each `scheme://host[:port]`; no `*`, path or trailing slash).
- `TaskflowCorsConfiguration.source(corsProperties)` builds a `UrlBasedCorsConfigurationSource` with two registrations, **in this order**:

| Pattern | Credentials | Methods | Request headers | Exposed headers | Max age |
|---|---|---|---|---|---|
| `/api/v1/auth/**` (first) | **allowed** | `GET, POST, PUT, PATCH, DELETE` | `Authorization, Content-Type, X-Correlation-Id` | `X-Correlation-Id, Location, WWW-Authenticate` | 1 h |
| `/api/v1/**` (second) | not allowed | same | same | same | 1 h |

- `TaskflowSecurityConfig`: `.cors(cors -> cors.configurationSource(TaskflowCorsConfiguration.source(corsProperties)))`, so the chain's `CorsFilter` runs before authentication and authorization. The source is not a bean.

**Rules.**
- **CORS lives in the security chain**, not only in Spring MVC: a preflight (`OPTIONS`) carries no credentials, so if it reached `authenticated()` it would get a 401 and the browser would report "CORS error" for every call. The chain's `CorsFilter` answers a valid preflight itself and stops there.
- **Pass the source explicitly.** Spring Security's fallback looks up a bean named exactly `corsConfigurationSource`; a bean with any other name is silently ignored and CORS is off.
- **The narrower pattern first.** `UrlBasedCorsConfigurationSource` returns the first match; `/api/v1/**` registered first would swallow `/api/v1/auth/**`, the refresh preflight would lack `Allow-Credentials`, and the browser would drop the cookie.
- **Exact origins, never `*` or patterns.** With credentials, `*` is refused by Spring (`CorsConfiguration.validateAllowCredentials`); `allowedOriginPatterns("*")` would be accepted and would let any website make a logged-in browser call refresh and read the new access token.
- **Credentials only where the cookie lives** (least privilege): the rest of the API authenticates with a header the script sets itself.
- **Expose `WWW-Authenticate` and `X-Correlation-Id`**: scripts can't read non-safelisted response headers otherwise, so the frontend couldn't tell "refresh and retry" (`invalid_token`) from "log in", or show a correlation id to support.
- **CORS protects reading, not the server.** A disallowed origin's simple request can still reach a server that has no CORS filter in front; that's why the cookie endpoints also check `Origin` (C9).

**Pitfalls.**
- A browser "CORS error" is often a 401 or 403 underneath: check the preflight's status in the network tab first.
- `Origin: null` (sandboxed iframes, some redirects) must never be in the list.
- curl ignores CORS: a working curl call proves nothing about browsers; the preflight table is the test.
- A rejected CORS request gets Spring's plain-text `Invalid CORS request` body, not a `ProblemDetail`. The page can't read it anyway (the browser hides the response of a failed CORS request); only curl-style callers that fake an `Origin` see it.

**Files.** `common/security/TaskflowCorsConfiguration`, `CorsProperties`, `TaskflowSecurityConfig`.

---

## Part D — Rebuilding this in another project: the recipe

Follow the steps in order; each one is runnable before the next. Every decision named here is explained in Parts A and C.

**Prerequisites:** a user table with a password-based login already working (Phase 1 equivalent), an injected `Clock` bean, a `ProblemDetail` error handler, a correlation-id filter, Flyway.

1. **Dependency:** `spring-boot-starter-oauth2-resource-server` (brings Nimbus JOSE). Start the app: nothing changes until you configure a decoder.
2. **Schema:** a sessions table (the family, absolute expiry, `revoked_at` + reason with a "reason iff revoked" check) and a refresh-tokens table (hash only, hash-format check, unique hash, `consumed_at`, partial unique index "one unconsumed token per session"). Cascade deletes from user → sessions → tokens. Verify in a rolled-back transaction.
3. **Configuration records,** validated at startup: JWT (`issuer`, `audience`, access TTL, clock skew, optional key triple, all or none) and sessions (idle TTL, absolute TTL, reuse grace, cookie name/path/secure/same-site). Redact secrets in `toString()`.
4. **Key configuration:** one signing-key bean (configured PEM, or an ephemeral pair in dev/test only, otherwise fail); modulus and size checks; a `NimbusJwtEncoder` over a one-key `JWKSet`.
5. **Access-token issuer:** RS256 + `kid` + `typ`; claims `iss, aud, sub, sid, preferred_username, role, iat, exp`; `iat` truncated to seconds; "now" passed in. Unit-test the content with `SignedJWT`.
6. **Entities and repositories:** insert-only entities (factories, no setters, `updatable = false`, microsecond truncation, masked `toString()`); conditional `@Modifying` updates returning row counts; a boolean liveness query; a scalar `consumed_at` query; projections for lists and claims.
7. **Session start + login:** a transactional `start` (session + first token, expiry capped); a non-transactional login that authenticates, reads the clock once, starts the session, records success, issues the token. Return the access token in the body and the refresh token in an `HttpOnly; Secure; SameSite=Strict; Path=<auth path>` cookie built by one class that also clears and reads it.
8. **Decoder:** `withPublicKey(...)`, RS256; validators via `createDefaultWithValidators(timestamp(skew, clock), exp-required, issuer, audience)`.
9. **Principal + converter:** a small principal record; a custom `AbstractAuthenticationToken` with one `ROLE_` authority; a converter (not a bean) that parses claims strictly and checks session liveness last, through an interface the session feature implements.
10. **Resolver, entry point, access-denied handler:** default resolver except the auth paths; problem-json writers; the entry point set in **both** `oauth2ResourceServer` and `exceptionHandling`.
11. **Chain:** remove Basic; resource server with the four pieces; `permitAll` for the auth endpoints; `denyAll()` last; stateless; CSRF off with the reason written. Then move every reader of the old principal (auditor, `@AuthenticationPrincipal`, re-authentication by email) and keep any lockout listener reacting only to password authentications.
12. **Refresh:** the six-step transactional `refresh` returning a result (Part C8); rollback-only on a dead session; a non-transactional workflow with the `Origin` check first; a controller that builds 200 + cookie or 401 + clearing cookie (except superseded).
13. **Events:** `REFRESH_TOKEN_REUSED`, `SESSION_REVOKED`, `ALL_SESSIONS_REVOKED`, with `MANDATORY` propagation.
14. **Revocation paths:** logout by cookie (+ `Origin`, always 204, always clears); a bearer-authenticated `/users/me/sessions` with list (`current` flag), revoke-one (owner in the `WHERE`, 404 for anything not revoked) and log out everywhere (this device included); `revokeAll` / `revokeAllExcept` (`MANDATORY`, null-guarded) called **inside** the password reset and change transactions.
15. **CORS:** a `UrlBasedCorsConfigurationSource` from the same exact-origin list; the auth path registered first with credentials, the rest without; methods, request headers, exposed headers (`WWW-Authenticate`, the correlation id, `Location`) and max age; `http.cors(c -> c.configurationSource(source))` passed explicitly.
16. **Prove it:** the 20-parallel-refresh race (exactly one 200, no 500); reuse after the grace window revokes the family and the winner's token dies too; a revoked session's access token gets 401 on the next request; `alg: none`, wrong key, wrong `aud`, expired → 401; an expired header on `/auth/refresh` is ignored; two devices: revoke one → its bearer and refresh 401, the other works; another user's session id → the same 404 as a random id; `DELETE …/sessions/` doesn't revoke all; reset → every session `PASSWORD_RESET`; change → others `PASSWORD_CHANGED`, the current works; preflights: the API path from the frontend → 200 without `Allow-Credentials`, the refresh path → 200 with it, a foreign origin → 403 without `Access-Control-Allow-Origin`; a real cross-origin call (including a 401) carries `Access-Control-Allow-Origin` and the exposed headers.

**Values we used** (adjust per project): access 15 min · refresh 14 days idle · session 30 days absolute · reuse grace 10 s · clock skew 5 s · RSA 2048 · cookie `taskflow_refresh` on `/api/v1/auth`.

---

## Part E — Quick reference: endpoints, errors, configuration

### Endpoints (Phase 2 so far)

| Method | Path | Auth | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/auth/login` | none (password in body) | 200 `{accessToken, tokenType, expiresIn, user}` + `Set-Cookie` | 400 `VALIDATION_FAILED` · 401 `AUTHENTICATION_FAILED` · 403 `EMAIL_NOT_VERIFIED` |
| POST | `/api/v1/auth/refresh` | refresh cookie | 200 `{accessToken, tokenType, expiresIn}` + `Set-Cookie` (rotated) | 401 `INVALID_REFRESH_TOKEN` (+ clearing cookie unless superseded) · 403 `ORIGIN_NOT_ALLOWED` |
| POST | `/api/v1/auth/logout` | refresh cookie (optional) | 204 + clearing `Set-Cookie` | 403 `ORIGIN_NOT_ALLOWED` |
| GET | `/api/v1/users/me/sessions?page&size` | bearer | 200 page of `{id, createdAt, lastRefreshedAt, expiresAt, ipAddress, userAgent, current}` | 401 |
| DELETE | `/api/v1/users/me/sessions/{id}` | bearer | 204 (+ clearing `Set-Cookie` if current) | 401 · 404 `SESSION_NOT_FOUND` |
| DELETE | `/api/v1/users/me/sessions` | bearer | 204 + clearing `Set-Cookie` | 401 |
| any | `/api/v1/**` (others) | `Authorization: Bearer` | as the endpoint | see the gate's errors below |

### CORS responses (since §2.5)

| Request | Response |
|---|---|
| Preflight to `/api/v1/**` from an allowed origin | 200, `Access-Control-Allow-Origin`, methods, headers, `Max-Age: 3600`, **no** `Allow-Credentials` |
| Preflight to `/api/v1/auth/**` from an allowed origin | 200, as above **plus** `Access-Control-Allow-Credentials: true` |
| Any CORS request (preflight or real) from a disallowed origin | 403, plain text `Invalid CORS request`, no `Access-Control-Allow-Origin` |
| Real request from an allowed origin (any status, 401 included) | `Access-Control-Allow-Origin` + `Access-Control-Expose-Headers: X-Correlation-Id, Location, WWW-Authenticate` |

### The gate's errors (filter chain)

| Situation | Status | Code | `WWW-Authenticate` |
|---|---|---|---|
| No token on a protected path (including `Basic …`) | 401 | `AUTHENTICATION_REQUIRED` | `Bearer` |
| Token presented and rejected (malformed, bad signature, expired, wrong `iss`/`aud`, bad claims, dead session) | 401 | `INVALID_ACCESS_TOKEN` | `Bearer error="invalid_token"` |
| Several bearer tokens in one request | 400 | `INVALID_ACCESS_TOKEN` | `Bearer error="invalid_request"` |
| Authenticated but not allowed | 403 | `ACCESS_DENIED` | — |

### Configuration

| Property | Value | Notes |
|---|---|---|
| `taskflow.security.jwt.issuer` / `.audience` | `taskflow` / `taskflow-api` | required |
| `taskflow.security.jwt.access-token-ttl` | `15m` | positive |
| `taskflow.security.jwt.clock-skew` | `5s` | not negative |
| `taskflow.security.jwt.key-id` / `.private-key` / `.public-key` | env or secret files only | all or none; prod requires them |
| `taskflow.security.sessions.refresh-token-idle-ttl` / `.absolute-ttl` | `14d` / `30d` | positive |
| `taskflow.security.sessions.reuse-grace` | `10s` | `0s` = strict detection |
| `taskflow.security.sessions.cookie.name` / `.path` / `.same-site` | `taskflow_refresh` / `/api/v1/auth` / `strict` | `secure` defaults to `true` |
| `taskflow.security.cors.allowed-origins` | dev/test `http://localhost:3000`; prod `${CORS_ALLOWED_ORIGINS}` | exact origins |

Environment variables in prod: `TASKFLOW_SECURITY_JWT_KEY_ID`, `TASKFLOW_SECURITY_JWT_PRIVATE_KEY`, `TASKFLOW_SECURITY_JWT_PUBLIC_KEY`, `CORS_ALLOWED_ORIGINS`.

---

## Part F — Framework behaviour we relied on (checked in the sources)

Spring Security 6.5.11 / Spring Framework 6.2.19 / Boot 3.5.16 / Hibernate 6.6, read in `~/.m2`.

| Behaviour | Consequence for the design |
|---|---|
| `NimbusJwtDecoder.setJwtValidator(x)` replaces the default validator | Use `JwtValidators.createDefaultWithValidators(...)` |
| `createDefaultWithValidators` adds its own timestamp validator unless it finds one **at the top level** of your list | Pass yours directly, not wrapped |
| `JwtTimestampValidator` defaults to `Clock.systemUTC()`, 60 s skew; passes a token without `exp` | `setClock`, explicit skew, an `exp`-required validator |
| `NimbusJwtDecoder.decode` rejects a `PlainJWT` (`alg: none`) before validating; `withPublicKey` uses a single-key selector (the `kid` isn't consulted) and expects `typ` JWT or none | Signature always checked first |
| `JwtAuthenticationProvider`: `BadJwtException` → `InvalidBearerTokenException` (401); other `JwtException` → `AuthenticationServiceException`; the converter runs after decoding | Session check in the converter; non-auth exceptions become 500 |
| `BearerTokenAuthenticationFilter` resolves and authenticates before authorization, even on `permitAll` paths; resolver exceptions go straight to the entry point | The resolver ignores `/api/v1/auth/**` |
| `OAuth2ResourceServerConfigurer` sets its entry point **on the filter** | Set ours in both places |
| `DefaultBearerTokenResolver`: header only by default; strict pattern; several tokens → `invalid_request` 400 | Kept as is outside the auth paths |
| `BearerTokenAuthenticationEntryPoint` adds `error_description` (the library's message) | Our entry point writes only the error code |
| Every successful bearer authentication publishes `AuthenticationSuccessEvent` | The lockout listener filters on the password principal type |
| `Jackson2ObjectMapperBuilder` registers Spring's `ProblemDetail` mixin | Writing a `ProblemDetail` with Boot's `ObjectMapper` matches MVC's output |
| Spring MVC writes a `ResponseEntity<ProblemDetail>` body as `application/problem+json` | The controller can build the refresh 401 itself |
| Boot adds every `Converter` bean to MVC's conversion service | The JWT converter is a plain object |
| Spring Security Test's `jwt()` always builds a `JwtAuthenticationToken` | Tests use `authentication(new TaskflowAuthenticationToken(...))` |
| Hibernate's HQL bulk `UPDATE` ignores `updatable = false` (entity flushes respect it) | Insert-only entities stay changeable by conditional updates |
| `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` in the outermost transaction rolls back silently on return *(seen in a scratch run)*; inside a caller's transaction it would cause `UnexpectedRollbackException` *(standard Spring behaviour, not run here)* | `refresh` must be called outside a transaction |
| Spring Framework 6 doesn't match a trailing slash by default: `DELETE /users/me/sessions/` matches neither `/sessions` nor `/sessions/{id}` → 404 *(seen in a scratch run)* | "Delete all" can't be reached by accident |
| Spring Data appends a `Pageable`'s sort to a JPQL constructor-expression query (it ran in a scratch run; the order itself wasn't asserted) | The session list sorts through `PageableFactory`, with no `ORDER BY` in the query |
| `UrlBasedCorsConfigurationSource.getCorsConfiguration` returns the **first** registered pattern that matches | Register `/api/v1/auth/**` before `/api/v1/**` |
| `CorsFilter`: a valid preflight is answered and the chain stops; an invalid CORS request (preflight **or** real, origin/method not allowed) is rejected by `DefaultCorsProcessor` with 403 and the plain body `Invalid CORS request`; same-origin requests aren't CORS requests and pass untouched | Preflights never reach authentication; a disallowed origin on `/auth/refresh` now gets the CORS 403 before `AllowedOrigins` *(both seen in a scratch run)* |
| `CorsConfiguration.validateAllowCredentials` throws when credentials are on and the origins contain `*` | Exact origins only |
| `CorsConfigurer` without an explicit source looks up a bean named exactly `corsConfigurationSource` | Pass the source to `http.cors(...)` |
| curl 8.7.1 keeps `Secure` cookies over `http://localhost`; jar lines for `HttpOnly` cookies start with `#HttpOnly_` | Dev needs no `secure: false`; don't grep jars for `^#` |

---

## Part G — Known gaps, debt and what comes next

**Phase 2 closed 2026-10-12.** §2.6 profile moved to Phase 3 (D17). The consolidated backlog (tests, open done-items, measurements, nits, later phases) is `PHASE_2_LEARNING_LOG.md` §8.

**Tests owed** (raised at phase close; Phase 3 D1 paid **Tier 1** on 2026-10-13: the revoked-session 401, every core refresh case including the 20-way race and the dead-session retry, reset/change revocation, session IDOR and the trailing slash, `prod` without a key. **The rest below is Tier 2, due in Phase 11**): §2.1's list (including `prod` refusing to start without a key), §2.2's beyond the integration tests that exist, all of §2.3's (every `refresh` branch with `Clock.fixed`; JPA slice for consume and touch; the integration list including the 20-way race and the dead-session retry that must **not** record reuse), all of §2.5's (unit: `CorsProperties` rejects `*`, a trailing slash, a path, an empty list; integration: each row of the preflight table, a disallowed origin gets no `Access-Control-Allow-Origin`, the refresh preflight allows credentials and the organizations preflight doesn't), and all of §2.4's (JPA slice: `revokeOwned` with another user's id → 0, `revokeAllExcept` spares exactly one, a second revoke keeps the first reason; the integration list in `PHASE_2_REQUIREMENTS.md` §2.4).

**Debt and nits:** a CORS rejection is plain text (`Invalid CORS request`), not a `ProblemDetail`: **accepted, D15** (browsers hide that body; a fix would need our own `CorsFilter`) · `revokeAll`/`revokeAllExcept` vs `revoke`/`revokeOwned` disagree on the `expires_at` condition · the ephemeral-key profile check should also require `prod` to be absent · `ProblemDetails` uses `Instant.now()` instead of the `Clock` · single-line PEM in env vars (Phase 11) · the default `spring.profiles.active: dev` in `application.yml` lets a profile-less deploy run as dev · Phase 9 cleanup of expired sessions and consumed tokens · Phase 10 rate limiting on login and refresh.

---

## Changelog

| Date | Sub-section | Change |
|---|---|---|
| 2026-10-10 | §2.1–§2.3 | Created: principles, architecture, C1–C10, recipe, quick reference, verified framework behaviour, gaps. §2.3 includes the dead-session rollback fix (a refresh that finds its session ended no longer consumes the token). |
| 2026-10-11 | §2.3 | Run passed (Postman + terminal: rotation, reuse after the grace window, 20-way race, superseded, `Origin`, expired, dead-session rollback, role change). D13 (rollback fix) and D14 (tests skipped) recorded in `PHASE_2_REQUIREMENTS.md`. |
| 2026-10-11 | §2.4 | Added C11 (logout, session list, revoke one, log out everywhere) and C12 (revocation inside password reset/change); revocation flow in Part B; recipe step 14; endpoints in Part E; trailing-slash and pageable-sort facts in Part F. The null-`sid` debt is closed (`revokeAllExcept` asserts it). Verified in a scratch copy (two devices, IDOR 404, trailing slash, logout cases, reset, change); your run and tests pending. |
| 2026-10-11 | §2.5 | Added C13 (CORS) and its rows in Parts B, D (step 15), E (CORS responses) and F (first match, `CorsFilter` behaviour, credentials + `*`, bean-name lookup). C9 notes that the CORS filter now rejects a disallowed cross-origin refresh/logout before `AllowedOrigins`. Verified in a scratch copy: every row of the preflight table, a cross-origin 401 carrying `Access-Control-Allow-Origin`; your run and tests pending. |
| 2026-10-12 | §2.4, §2.5, close | Both run in full by Claude on a scratch copy + scratch DB (every row, §2.4 deliberate failures 1–4, §2.5 1–3; suite 83/83, 12 s, 2 containers). D15 (CORS plain-text 403 accepted), D16 (no §2.4/§2.5 tests), D17 (§2.6 → Phase 3). Phase 2 closed. |
| 2026-10-13 | tests | Phase 3 §3.0 added automated proof for Part D step 16's core cases (revoked session 401, rotation/reuse/superseded, the 20-way race, the dead-session rollback, reset/change revocation, session IDOR, trailing slash) and for C1's "prod refuses without a key". Test tools: `AdjustableClock`, `Concurrently`, `TestLogins.login/cookieHeader/sessionIdOf`. |
| 2026-10-13 | close | Coverage table: §2.4 + §2.5 are committed (`b6f3dde`). Test-debt scheduling raised as Phase 3's D1. Phase 3 will change C5's world: a Hibernate tenant resolver runs at every session open (Phase 3 §3.4). |
