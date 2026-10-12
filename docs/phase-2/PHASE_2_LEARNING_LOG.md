# Phase 2 — Learning Log (JWT & sessions)

> Organised by **theme**, not by sub-section (rule since 2026-10-01). Sub-section detail (briefs, requirements, per-step deliberate failures, test plans) lives in `PHASE_2_REQUIREMENTS.md`.
> Companions: `../phase-1/PHASE_1_LEARNING_LOG.md` (the identity this phase builds on) · `../phase-1/SECURITY_TESTING_GUIDE.md`.
> **Phase closed 2026-10-12.** Covers §2.1 (2026-10-07) · §2.2 (2026-10-09) · §2.3 (2026-10-11) · §2.4 + §2.5 (2026-10-12). §2.6 profile moved to Phase 3 (D17).
> The rebuild recipe (what, how, rules) is your `PHASE_2_IMPLEMENTATION_REFERENCE.md`; the theory is `JWT_AND_SESSIONS_GUIDE.md`.

---

## 1. What was built

| Capability | How it works | Key pieces |
|---|---|---|
| **Signing keys** | RS256 key pair from typed config (PEM), checked: public key must share the private key's modulus, at least 2048 bits. `dev`/`test` without a key generate an ephemeral pair and log one `WARN`; any other profile refuses to start. | `JwtProperties`, `JwtKeyConfig`, `JwtSigningKey` |
| **Access tokens** | `NimbusJwtEncoder`, header `RS256` + `kid` + `typ JWT`; claims `iss, aud, sub` (user id), `sid, preferred_username, role, iat, exp`. **No email.** `iat` truncated to whole seconds, `exp = iat + 15 min`. | `AccessTokenIssuer`, `IssuedAccessToken`, `TaskflowClaims` |
| **Sessions** | V5: `user_sessions` (the family: 30-day absolute expiry, revocation switch) + `refresh_tokens` (SHA-256 hash, 14-day idle expiry capped at the session's end, one unconsumed token per session by partial unique index). Insert-only entities; every change is a conditional `@Modifying` update. | V5, `UserSession`, `RefreshToken`, repositories, `SessionService.start` |
| **Login** | Authenticate (unchanged) → start session → record `LOGIN_SUCCEEDED` → issue token. Body `{accessToken, tokenType, expiresIn, user}`; refresh token in an `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` cookie. | `LoginService`, `AuthController`, `RefreshCookie`, `LoginResponse`, `LoginResult` |
| **Bearer authentication** | Resource server with our own decoder: RS256 only; validators = timestamps (injected `Clock`, 5 s skew) + **`exp` required** + issuer + audience, through `createDefaultWithValidators`. `SessionJwtConverter` (not a bean) parses `sub`/`sid`/`preferred_username`/`role` strictly, then asks `SessionStatus` (one boolean query) → `TaskflowAuthenticationToken` with an `AuthenticatedUser`. The resolver ignores `/api/v1/auth/**`. **Basic deleted.** | `JwtKeyConfig#jwtDecoder`, `SessionJwtConverter`, `SessionStatus` / `JpaSessionStatus`, `TaskflowAuthenticationToken`, `AuthenticatedUser`, `TaskflowBearerTokenResolver`, `TaskflowSecurityConfig` |
| **JSON 401/403** | Entry point (both in `oauth2ResourceServer` and `exceptionHandling`) and access-denied handler write `ProblemDetail` directly (no `/error` dispatch): `AUTHENTICATION_REQUIRED` 401 + `WWW-Authenticate: Bearer`, `INVALID_ACCESS_TOKEN` 401 (400 for several tokens) + `error="…"` only, `ACCESS_DENIED` 403. Fixed details, never exception messages. | `ProblemAuthenticationEntryPoint`, `ProblemAccessDeniedHandler`, `ProblemResponseWriter`, `ProblemDetails` (shared with `GlobalExceptionHandler`) |
| **Principal readers** | Auditor, `UserController`, `changePassword` (email loaded by id) read `AuthenticatedUser`; the lockout listener still reacts only to `TaskflowPrincipal`. `UserRole` moved to `common/security`. | `AuditAwareImpl`, `PasswordWorkflow`, `UserAccountRepository#findEmailById`, `AuthenticationEventsListener` |
| **Refresh** | `POST /auth/refresh` with the cookie: shape check → find by hash → **conditional consume** → on 0 rows classify (expired · consumed within 10 s = superseded, cookie untouched · consumed earlier = reuse: revoke the session + `REFRESH_TOKEN_REUSED`, committed) → **conditional touch** (dead session → rollback-only, so the token isn't burnt) → current username and role → successor capped at the session's end. Returns sealed results; never throws for an expected rejection. One client code, `INVALID_REFRESH_TOKEN`. | `SessionService#refresh`, `RefreshResult`, `SessionWorkflow`, `RefreshOutcome`, `RefreshResponse`, `AuthController#refresh`, `RefreshCookie#read`, `AccountClaims`, `SessionErrorCode` |
| **`Origin` check + events** | Refresh (and §2.4's logout) refuse a present, non-allowed `Origin` with 403 `ORIGIN_NOT_ALLOWED` before the cookie is read; absent `Origin` is allowed. V6 adds three event types; their recorder methods are `MANDATORY`. | `AllowedOrigins`, `CorsProperties` (§2.5 will reuse it), `RequestForbiddenException`, V6, `SecurityEventRecorder` |
| **Logout and sessions** | `POST /auth/logout` (cookie + `Origin`; always 204, clears the cookie; revokes the cookie's session, no event) · `GET /users/me/sessions` (live sessions, `current` flag, IP as a literal, sorted through `PageableFactory`) · `DELETE /users/me/sessions/{id}` (owner in the `WHERE`; every miss the same 404; `SESSION_REVOKED`) · `DELETE /users/me/sessions` (all, this one included; `ALL_SESSIONS_REVOKED`) | `SessionService` (`revokeByRefreshToken`, `revokeOne`, `logOutEverywhere`, `listLive`), `SessionController`, `SessionResponse`, `SessionWorkflow`, `AuthController#logout` |
| **Revocation on password events** | Reset → `revokeAll(PASSWORD_RESET)`; change → `revokeAllExcept(current sid, PASSWORD_CHANGED)`; both `MANDATORY`, inside the password transaction; `revokeAllExcept` asserts a non-null id | `SessionService#revokeAll/#revokeAllExcept`, `PasswordService`, `PasswordWorkflow` |
| **CORS** | In the security chain, source passed explicitly (not a bean): `/api/v1/auth/**` first with credentials, `/api/v1/**` without; exact origins from `CorsProperties`; exposes `X-Correlation-Id`, `Location`, `WWW-Authenticate` | `TaskflowCorsConfiguration`, `TaskflowSecurityConfig` |
| **Client IP parsing** | Moved from `SecurityEventRecorder` into `ClientInfo.inetAddress()`, shared by events and sessions. | `ClientInfo` |

**Not built:** §2.6 profile (moved to Phase 3, D17). **Tests:** §2.2's migration + 5 integration tests only; nothing for §2.1, §2.3, §2.4, §2.5 (D10, D12, D14, D16).

**Commits:** `9b10252` (§2.1) · `9e07cbb` (§2.2) · `34c15f2` (§2.3) · §2.4 + §2.5 not yet committed at phase close.

### Evidence

| | |
|---|---|
| **Verified by me** | V5 + V6 in a rolled-back transaction on the dev DB (2026-10-01: every constraint, the partial unique index, the 1-then-0 conditional consume, the cascade, the plans) · V5 as committed matches the brief (indentation only) · curl 8.7.1 keeps a `Secure` cookie over `http://localhost` (throwaway server, 2026-10-07) · the framework behaviour marked *(verified)* below, from 6.5.11 / Hibernate 6.6.53 sources · the code, by reading (review 2026-10-06) |
| **Reported by you** | Login body and cookie; the decoded token (header `kid ephemeral-…`, `RS256`; claims as agreed, `exp − iat = 900`, no email); no session on a wrong password or an unverified login; the ephemeral-key `WARN`; deliberate failures 1–4 "working as expected" (details, and which error the `prod` run showed, not sent) |
| **Reported by you (§2.2, 2026-10-09)** | All 14 rows of the run table "working as expected": bearer 200 · no token / Basic → 401 `AUTHENTICATION_REQUIRED` · `createdBy` = username · 403 `ACCESS_DENIED` bodies · expired token (1-min TTL) → 401 · `alg: none` → 401 · session revoked by SQL → 401 at once · expired header ignored on `/auth/login` · change-password with a bearer token · login history · restart → 401 · deliberate failures 1–5 · suite green (count, time and containers **not reported**) |
| **Verified by me (§2.2 review)** | `createDefaultWithValidators` varargs exists and skips its default timestamp validator when it finds yours at the top level · `JwtTimestampValidator` passes a token with **no** `exp` · `Jackson2ObjectMapperBuilder` adds Spring's `ProblemDetail` mixin · `withPublicKey(...)` uses a `SingleKeyJWSKeySelector` (the `kid` isn't consulted) |
| **Reported by you (§2.3, 2026-10-11)** | Every row of the Postman/terminal run "working as expected": rotation (old consumed, new live, `last_refreshed_at` moved) · old token after 10 s → 401 + clearing cookie, session `REFRESH_TOKEN_REUSE`, event, the winner's token and access token dead · **20-way race: one 200, no 500** · two at once: one 200, one 401 without `Set-Cookie` · foreign `Origin` → 403, token unconsumed · no cookie → 401 · expired → 401, no event · **revoked session refreshed twice → no consume, no false reuse event (D13)** · role change reaches the next token · deliberate failures 2–6 |
| **Verified by me (§2.3 review)** | `AbstractPlatformTransactionManager.commit` (spring-tx 6.2.19): local rollback-only on the outermost transaction → silent rollback, no exception |
| **Verified by me (§2.4 + §2.5, 2026-10-12)** | Run in full on a **scratch copy + scratch database** (your dev DB untouched; scratch DB dropped): two devices listed with `current` · revoke-one → that device 401 + refresh 401, `REVOKED_BY_USER`, one `SESSION_REVOKED` · repeat / random id / **another user's id → three byte-identical 404s** (bar timestamp, correlation id, path), the other session still live · trailing-slash `DELETE` → 404, nothing revoked · revoking your own session clears the cookie · log out everywhere → both devices 401 + refresh 401, `LOGOUT_ALL`, exactly one event · logout → 204, `LOGOUT`, no event, idempotent, no cookie → 204 · change → own session works, the other `PASSWORD_CHANGED` · reset → every session `PASSWORD_RESET`, none live · CORS preflight table (credentials only on `/auth/**`, foreign origin 403) · foreign `Origin` on logout/refresh → plain-text `403 Invalid CORS request`, nothing revoked or consumed · **suite 83/83, 12 s wall, 2 Postgres containers** (Phase 1 ended at 76 / 2) · `CorsFilter` behaviour in spring-web 6.2.19 sources |
| **Not proven** | That `prod` without a key refuses to start **because of the key** (the missing `EmailSender` may fail first) · a wrong-`aud` token (never exercised) · anything in §2.1, §2.3–§2.5 by an automated test |

---

## 2. Decisions

Dated, with alternatives, in `PHASE_2_REQUIREMENTS.md`'s Decisions table. One line each:

- **D1** Spring's resource server with our own decoder/converter, not a hand-written filter (custom filter + provider is Phase 10's lesson).
- **D2** RS256, `kid` from day one, ephemeral dev/test key, prod fails without a key. Opaque refresh tokens make key rotation log nobody out.
- **D3** Access 15 min · refresh 14 days idle · session 30 days absolute.
- **D4** Refresh token in an `HttpOnly` cookie scoped to `/api/v1/auth`; access token in the body, kept in memory.
- **D5** Per-request session check (`sid` → one PK lookup): revocation on the next request. Closes Phase 1's "reject tokens issued before `password_changed_at`".
- **D6** Reuse revokes the family; 10 s grace for concurrent refreshes.
- **D7** Reset → all sessions; change → all others; lockout → none; log out everywhere → all.
- **D8** Session list and revoke-one are built (first to trim after §2.6).
- **D9** Phase 1 test debt deferred; raised when Phase 2 closes.
- **D10** §2.1 tests skipped; raised with D9.
- **D12** §2.2's own test list skipped (migration + 5 integration tests written); raised with D9/D10.
- **Converter not a bean** *(your call, §2.2)*: Boot adds every `Converter` bean to MVC's conversion service.
- **`exp` required by an explicit validator** *(your call, §2.2; my brief missed the gap)*.
- **D13** *(your approach, better than my fix)* A refresh that finds its session dead marks the transaction rollback-only: the token isn't burnt, also when the session dies between lookup and consume.
- **D14** §2.3 tests skipped (I recommended against).
- **D15** A disallowed cross-origin request gets Spring's plain-text CORS 403: accepted (browsers can't read it; `AllowedOrigins` stays as the backstop).
- **D16** §2.4/§2.5 tests not written: the phase closed without them at your instruction.
- **D17** §2.6 profile moved to Phase 3 as a side task.
- **D11** *(your approach, better than the brief)* Insert-only session entities: mutable columns `updatable = false`, changes only by conditional bulk updates.
- `CookieSettings` naming in the doc (deliberate, 2026-10-07).

---

## 3. Lessons, by theme

***(Hit)*** = actually happened in this project.

### Tokens

- **A JWT is signed, not encrypted**: anyone holding it reads the payload. Claims carry ids, never PII (deliberate failure 1).
- **JWT times are whole seconds** (`NumericDate`). Truncate `iat` before computing `exp`, or the `expiresAt` you return differs from the token's `exp`.
- **A one-element audience is written as a plain string** (`"aud": "taskflow-api"`), not an array *(seen in your token; I had predicted an array)*. RFC 7519 allows both; Spring's `Jwt.getAudience()` returns a list either way.
- **`kid` + opaque refresh tokens make key rotation cheap:** a token signed by a removed key gets 401, the client refreshes, nobody logs in again.
- **An ephemeral key is per process:** a restart (or a second instance) invalidates every access token. Fine for dev, never for prod.
- **The signature is checked first; nothing else runs on a token it rejects.** An unexpired token is valid until `exp` *and only while the server holds the key that signed it* *(seen: row 12, a restart → 401)*. `withPublicKey(...)` uses a `SingleKeyJWSKeySelector`, so the `kid` header isn't even consulted *(verified)*. Changing the key revokes every access token at once; a leaked private key is the worst case.
- **A token with no `exp` passes the timestamp validator** *(verified; caught by your code, missed by my brief)*. Require `exp` explicitly.

### Rotation, reuse and concurrency

- **One conditional `UPDATE`, branch on the row count** decides the single winner of N concurrent refreshes *(seen: 20 parallel → one 200, no 500)*. The losers' `UPDATE`s wait on the winner's row lock, then re-check `consumed_at IS NULL` against the committed row and match nothing (PostgreSQL's documented READ COMMITTED behaviour).
- **After losing, re-read with a scalar query**: the entity loaded before the race still says `consumedAt = null`.
- **The grace window is measured from `consumed_at`**, and a loser's "now" can be *earlier* than the winner's `consumed_at`: still inside the window.
- **Expired ≠ reused.** Only a consumed token signals theft.
- **A detection that must survive its own failure response is returned, not thrown**: an exception inside `@Transactional` rolls back the revocation and the event *(deliberate failure 2, seen)*.
- **Every write that a rejection leaves behind must be judged** *(caught in review, D13)*: the committed consume of a dead session's token turned a client's later retry into a false theft signal. `setRollbackOnly()` + return undoes it without an exception, **but only in the outermost transaction**: inside a caller's transaction the mark spreads and the caller's commit throws `UnexpectedRollbackException` *(verified in spring-tx)*. My brief had called that consume "harmless".
- **The loser must not clear the winner's cookie** *(deliberate failure 4, seen)*.
- **`MANDATORY` on event methods that must commit with a revocation** makes a missing transaction loud.

### Cookie endpoints and `Origin`

- **`SameSite=Strict` stops other sites, not sibling subdomains**: the `Origin` check closes that. Check it **before** reading the cookie, so a foreign page can't burn the token *(seen: 403, token unconsumed)*.
- **Absent `Origin` is allowed** (non-browser clients send none; CSRF needs a browser, which always sends it on a cross-origin POST).
- **A rejection that must set a header is built in the controller**, not thrown: an exception handler wouldn't add the clearing `Set-Cookie`.
- **Postman's cookie jar can't tell old from new tokens**: send `Cookie: name={{var}}` yourself with the jar disabled, and confirm in the Console that exactly one cookie went out *(your setup, 2026-10-11)*. Concurrency needs a terminal: the runner is sequential.

### Revocation and session management

- **Revocation is one row, so every way of ending a login is one conditional `UPDATE`**, and "not revoked yet" in its `WHERE` keeps the first reason on a second revocation.
- **Put the owner in the `WHERE`, and answer every miss with the same 404** *(deliberate failure 1, seen: without it, alice logged bob's device out)*. Ids from a global sequence are guessable; a 403 would confirm they exist.
- **Revocation commits in the password's transaction or not at all** *(deliberate failure 2, seen: moved to a separate step, the reset committed, the step failed with `IllegalTransactionStateException` (`MANDATORY`), the client got a 500, and the old session kept working with the new password set)*.
- **An assert turns SQL's silent `NULL` into a loud failure** *(deliberate failure 4, seen: null sid → 500 and the whole change rolled back, instead of "changed, nothing revoked")*.
- **Cookie identity is name + domain + path** *(deliberate failure 3, seen: a clearing cookie with `Path=/` left the real one in the jar)*; a response from `/users/me/...` may clear a cookie whose `Path` is `/api/v1/auth`.
- **Logout uses the cookie, not the bearer token**, so it works after the access token expires; it's idempotent (always 204).
- **Spring Framework 6 doesn't match a trailing slash**: `DELETE /users/me/sessions/` is a 404, not "delete all" *(seen)*.
- **A fixed `ORDER BY` plus a Pageable sort sorts twice**: keep the order in one place (`PageableFactory`).

### CORS

- **CORS must run inside the security chain** *(deliberate failure 1 of §2.5, seen: without `http.cors`, the preflight got 401 `WWW-Authenticate: Bearer`)*.
- **`CorsFilter` also rejects disallowed *actual* requests**, not just preflights: plain-text `403 Invalid CORS request`, before any of our code *(verified in spring-web 6.2.19; seen on refresh and logout)*. Same-origin requests aren't CORS requests and pass through.
- **First registration wins** *(§2.5 #2, seen: `/api/v1/**` first → the refresh preflight lost `Allow-Credentials`)*.
- **Validate origins at startup** *(§2.5 #3, seen: `*` → "APPLICATION FAILED TO START", naming `allowedOrigins[0]`)*.
- **A browser can't read a failed CORS response**, so the error format of a CORS rejection matters only to curl-style callers (D15).
- **`getHostAddress()` prints IPv6 in full** (`0:0:0:0:0:0:0:1` for `::1`): cosmetic.

### Keys and configuration

- **An RSA pair shares its modulus** (`n`): comparing moduli proves the public key belongs to the private key, at startup instead of at the first failed verification.
- **Fail closed on missing secrets, at startup.** Check the dangerous case, not only the safe ones: "ephemeral if `dev` or `test`" still lets a misconfigured `prod,dev` through (nit).
- **`RsaKeyConverters` expects a multi-line PEM.** A single-line env var with escaped `\n` (common in compose/k8s) fails: Phase 11.
- **Validate config in the record's compact constructor** for cross-field rules ("all three key fields or none"); Bean Validation for single fields. Redact secrets in `toString()`.

### Persistence

- **`updatable = false` governs entity flushes only.** HQL bulk updates still write those columns *(verified: Hibernate 6.6.53's `BaseSqmToSqlAstConverter.visitSetClause` never checks updatability)*. Together they make "insert with the entity, change only by conditional `UPDATE`" enforceable.
- **Truncate to the database's precision in the factory** (`MICROS` for `timestamptz`): in-memory values then equal the stored row (Phase 0 testing guide §6.6).
- **`id <> NULL` is unknown, not true:** `revokeAllExcept(account, null, …)` would revoke **nothing**, silently *(caught in review; my brief had predicted the opposite)*. Assert non-null before any `<>` on a parameter.
- **A `Long` id instead of a relation** (`UserSession.userAccountId`), like `SecurityEvent`: the per-request check needs no join.
- **Applied migrations are frozen** (now V1–V5).

### Cookies

- **curl keeps `Secure` cookies over `http://localhost` and `127.0.0.1`** *(verified, curl 8.7.1)*, and sends them only under their `Path`.
- **In a curl jar, an `HttpOnly` cookie's line starts with `#HttpOnly_`**: it looks like a comment *(hit: my first check grepped it away and reported "no cookie")*.
- **Cookie identity is name + domain + path:** clearing needs the same name and `Path`, which is why one class (`RefreshCookie`) builds both.

### Spring Security resource server (verified in 6.5.11 sources; built in §2.2)

- `setJwtValidator(x)` **replaces** the default validator, the only one checking `exp`; use `JwtValidators.createDefaultWithValidators`.
- `JwtTimestampValidator` uses `Clock.systemUTC()` unless given a clock; default skew 60 s.
- Validators don't short-circuit; the converter runs only after decoding and validation succeed.
- An invalid bearer token is rejected **before** authorization, even on `permitAll` paths.
- The bearer filter has its own entry point (set by `OAuth2ResourceServerConfigurer`); `exceptionHandling` alone leaves half the 401s with Spring's body.
- Every bearer request publishes `AuthenticationSuccessEvent` (`HttpSecurity`'s manager has Boot's publisher).
- `alg: none` → `PlainJWT` → rejected before validation. A malformed `Bearer` header → 401 `invalid_token`; several tokens → 400 `invalid_request`.
- spring-security-test's `jwt()` always builds `JwtAuthenticationToken`.
- `createDefaultWithValidators` adds its own timestamp validator (system clock, 60 s) unless it finds a `JwtTimestampValidator` **at the top level** of your list: wrap yours in another delegating validator and a second one appears.
- A `Converter` **bean** ends up in MVC's conversion service (Boot adds them): keep the JWT converter a plain object.
- Basic credentials now reach the app as "no bearer token" (the resolver only reads `Bearer …`): `AUTHENTICATION_REQUIRED`, not `INVALID_ACCESS_TOKEN`.
- An entry point that writes the body itself avoids the `/error` dispatch; Boot's `ObjectMapper` carries the `ProblemDetail` mixin, so the extra properties come out at the top level like MVC's.

### The principal

- **Everything that reads the principal follows its type.** `@AuthenticationPrincipal(errorOnInvalidType = true)` fails loudly (500); the auditor fails **silently** (`system`) *(deliberate failures 4 and 5, seen)*. The auditor test now pins the type both ways.
- **A success event fires on every bearer request** *(verified)*: the lockout listener must keep reacting only to password authentications, or the owner's open tab resets the counter while an attacker guesses.
- **The bearer principal has no email**, so re-authentication loads it by id. Never add PII to the token to save a query.
- **`common` can't import a feature**: the converter parses roles, so `UserRole` moved to `common/security`.
- Boot creates a `JwtDecoder` only from `spring.security.oauth2.resourceserver.jwt.*` properties, and adds its own chain only without yours.
- CORS: `CorsConfigurer` looks up a bean named exactly `corsConfigurationSource`; `UrlBasedCorsConfigurationSource` returns the first match in registration order; credentials + `*` throws.

---

## 4. Production practices, and what each prevents

| Practice | Prevents |
|---|---|
| Asymmetric signing, `kid`, keys from env, modulus + size check, prod refuses to start without a key | Anything that verifies being able to mint tokens; a mismatched pair discovered at the first request; a prod instance signing with a throwaway key |
| No PII in claims; ids in `sub` and `sid` | Emails leaking through every log and ticket that captures a token |
| Opaque refresh token, SHA-256 at rest, one live token per session by index | A long-lived unrevocable credential; a DB leak handing out sessions; forked families |
| Insert-only entities + conditional updates | A stale entity write resurrecting a revoked session |
| One clock reading per login, truncated to storage precision | Off-by-microseconds comparisons; session and token times disagreeing |
| Session before `LOGIN_SUCCEEDED`; login not transactional | History saying "succeeded" for a 500; the Phase 1 rollback trap |
| Masked `toString()` on every type holding a token | Raw refresh or access tokens in logs |
| `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` | XSS stealing the 14-day credential; the cookie travelling with every API call |
| Decoder pinned to RS256; `exp` required; validators added, never replacing the defaults | `alg: none` and algorithm confusion; immortal tokens; expiry silently off |
| Per-request session check after all token checks, errors not caught | Revoked sessions still working; DB queries for garbage tokens; outages reported as 401 |
| One `ProblemDetail` factory for MVC and the filter chain; fixed details; entry point set in both places | Two error shapes; JWT parse messages leaking; half the 401s with Spring's body |
| Resolver ignores `/api/v1/auth/**` | A stale token locking a client out of login and refresh |
| Conditional consume + scalar re-read + partial unique index; grace from `consumed_at` | Two winners; a forked family; the multi-tab wake-up logging users out |
| Reuse detection returned (committed) not thrown; `MANDATORY` events; rollback-only for a dead session | Detection that forgets itself; events without their revocation; false theft signals |
| `Origin` checked first; one error code for every refresh failure; clearing cookie except when superseded | Sibling-subdomain CSRF; an oracle for thieves; the loser deleting the winner's cookie |
| Owner in the `WHERE`; one 404 for every miss; owner from the principal, never the URL | IDOR; confirming that guessed ids exist |
| Revocation inside the password transaction, `MANDATORY`; `Assert.notNull` before a `<>` | "Password changed, attacker still in"; silent no-op revocations |
| CORS in the chain, explicit source, narrow pattern first, exact validated origins, credentials only on `/auth/**` | Preflight 401s; CORS silently off; the cookie dropped; any website reading responses |

---

## 5. Interview questions

**Answerable now**
1. **What's inside your access token, and what isn't?** `iss, aud, sub` (id), `sid, preferred_username, role, iat, exp`; no email, because the payload is only base64url.
2. **RS256 or HS256?** RS256: with HMAC, whatever verifies can mint; the public key can be shared.
3. **How do you rotate the signing key without logging anyone out?** `kid`; refresh tokens are opaque, so clients with a now-invalid access token just refresh.
4. **Why is the refresh token opaque and the access token a JWT?** The access token is verified on every request without state; the refresh token is checked against the DB every time, so it can be killed.
5. **Where do you keep the refresh token, and why not `localStorage`?** An `HttpOnly` cookie scoped to `/api/v1/auth`: XSS can't read it.
6. **How do you prove a public key belongs to a private key?** Same modulus.
7. **Why truncate `iat` to seconds?** JWT times are whole seconds; otherwise `expiresIn` disagrees with `exp`.
8. **How did you make it impossible for an entity save to undo a revocation?** `updatable = false` on every mutable column; changes only through conditional bulk updates, which Hibernate still executes.
9. **Why would `WHERE id <> :except` revoke nothing?** `NULL` comparisons are unknown in SQL.
10. **The session id is in a readable token. Is that a leak?** No: it's an identifier, not a credential; the signature stops anyone changing it, and the owner check stops anyone using someone else's.

11. **Walk me through a bearer request.** `BearerTokenAuthenticationFilter` (slot before Basic's) → resolver → `JwtAuthenticationProvider` → decoder: signature, then all validators → converter: claims, then the session lookup → context set → authorization.
12. **How do you revoke a JWT?** You can't change the token; each request checks the session it carries (`sid`), one PK lookup. Revoked by SQL → 401 on the next request (seen).
13. **Why was an unexpired token rejected after a restart?** The dev key is per process; the signature check fails before expiry is looked at.
14. **Why is an invalid token rejected even on `permitAll`?** Authentication runs before authorization; our resolver ignores tokens on `/auth/**`.
15. **401 vs 403, and `WWW-Authenticate`?** Unidentified (with `Bearer` / `error="invalid_token"` so the client knows to refresh or log in) vs identified and refused.
16. **Your custom validator could have accepted expired tokens. How?** `setJwtValidator` replaces the defaults; and a token with no `exp` passes the timestamp check.

17. **Two tabs refresh at the same moment with one cookie. What happens?** Row lock → one winner; the loser re-reads `consumed_at`, sees it milliseconds old → superseded: 401 without touching the cookie; nothing revoked.
18. **How does rotation catch a stolen refresh token?** Whoever presents a consumed token after the grace window proves a copy exists; the server can't tell which copy is the thief's, so the whole session dies, and the event is recorded.
19. **Why does the reuse branch return instead of throwing?** The revocation and event must commit with the 401.
20. **Cookies bring CSRF back. How are refresh and logout protected?** `SameSite=Strict`, `Path=/api/v1/auth`, and an `Origin` allow-list checked before the cookie is read.
21. **A refresh found its session revoked. Why did you roll back instead of just rejecting?** A committed consume would make the client's later retry look like theft.

22. **A user presses "log out everywhere". What happens to the attacker's access token?** Its session row is revoked in one `UPDATE`; the next request's session check fails → 401; refresh fails too.
23. **Why 404, not 403, for someone else's session id?** Ids are guessable; a 403 confirms existence. The owner is in the `WHERE`, so it's one code path.
24. **How do you make sure a password reset logs the attacker out?** The revocation runs in the reset's transaction (`MANDATORY`); a separate step can fail after the password committed (seen).
25. **Why must CORS be configured in the security chain?** Preflights carry no credentials; otherwise they hit `authenticated()` and get 401.
26. **CORS vs CSRF?** CORS controls whether a script may *read* a cross-origin response (and send credentials); CSRF is a forged request riding on a cookie the browser attaches. SameSite + `Origin` handle CSRF here; CORS doesn't.

**Not answerable yet:** timing of login vs unknown email (unmeasured, Phase 1) · the per-request session lookup's plan at scale (unmeasured).

---

## 6. Commands

```bash
./mvnw spring-boot:run                                                     # dev: look for the ephemeral-key WARN
curl -s -i -c /tmp/tf.jar -H 'Content-Type: application/json' -d '{"email":"<email>","password":"<pw>"}' localhost:8080/api/v1/auth/login
cat /tmp/tf.jar                                                            # the cookie line starts with #HttpOnly_localhost
# decode header and payload of a JWT
python3 -c 'import sys,base64,json;[print(json.dumps(json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4))),indent=2)) for p in sys.argv[1].split(".")[:2]]' "<accessToken>"
# §2.2: a 1-minute access lifetime, to see expiry
./mvnw spring-boot:run -Dspring-boot.run.arguments=--taskflow.security.jwt.access-token-ttl=1m
curl -s -i -H "Authorization: Bearer $T" localhost:8080/api/v1/organizations
# an alg:none token claiming ADMIN (must be 401)
python3 -c 'import base64,json;e=lambda o:base64.urlsafe_b64encode(json.dumps(o).encode()).rstrip(b"=").decode();print(e({"alg":"none","typ":"JWT"})+"."+e({"sub":"1802","sid":"1952","preferred_username":"x","role":"ADMIN","iss":"taskflow","aud":"taskflow-api","exp":4102444800})+".")'
# verify a change WITHOUT touching your dev DB: scratch copy + scratch database (what the 2026-10-12 run did)
rsync -a --exclude target --exclude .idea --exclude .git ./ /tmp/tf-scratch/ && docker exec taskflow-postgres psql -U taskflow -d taskflow -c 'create database taskflow_scratch'
(cd /tmp/tf-scratch && ./mvnw -q -DskipTests -Dmaven.gitcommitid.skip=true spring-boot:run -Dspring-boot.run.arguments="--server.port=18080 --spring.datasource.url=jdbc:postgresql://localhost:5432/taskflow_scratch")
docker exec taskflow-postgres psql -U taskflow -d taskflow -c 'drop database taskflow_scratch'   # afterwards
# prod without a key (needs the DB variables; the missing EmailSender may fail first)
DB_HOST=localhost DB_PORT=5432 DB_NAME=taskflow DB_USER=taskflow DB_PASSWORD=taskflow_local_dev FRONTEND_BASE_URL=http://localhost:3000 ./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

```sql
select id, user_account_id, created_at, expires_at, last_refreshed_at, revoked_at, revoke_reason, host(ip_address) ip, user_agent from user_sessions order by id desc limit 5;
select id, session_id, left(token_hash, 12) hash, created_at, expires_at, consumed_at from refresh_tokens order by id desc limit 5;
update user_sessions set revoked_at = now(), revoke_reason = 'LOGOUT' where id = <sid>;   -- revoke by hand: the next request with that token is 401
```

---

## 7. Deliberate failures and mutation checks

**Seen** (reported by you, 2026-10-07, details not sent): §2.1 #1 email claim readable · #2 unmasked result logs the raw token · #3 second unconsumed token refused by `uk_refresh_tokens_active` · #4 `prod` without a key refuses to start (which error appeared first: not reported).

**Seen in §2.2** (reported 2026-10-09, "working as expected"): #1 audience-only validator → expired token accepted · #2 resolver without the `/auth/**` rule → login 401 with a stale header · #3 entry point in one place → empty 401 body · #4 `@AuthenticationPrincipal TaskflowPrincipal` → 500 · #5 auditor on the old type → `created_by = system` · #6 `alg: none` → 401 · #7 restart → 401 · #8 session revoked by SQL → 401 at once.

**Seen in §2.3** (reported 2026-10-11, "working as expected"): #1 check-then-act consume, 20 parallel → several 200s then a 500 · #2 throw from the reuse branch → session still live, no event · #3 grace 0, two at once → winner logged out · #4 superseded clears the cookie → empty jar · #5 entity touch → blocked by `updatable = false` (D11) · #6 foreign `Origin` → 403, nothing consumed.

**Seen in §2.4 + §2.5** (ran by me on the scratch copy, 2026-10-12): §2.4 #1 no owner in the query → another user's device logged out (204, their next request 401) · #2 revocation as a separate step after the reset → 500 (`IllegalTransactionStateException`, `MANDATORY`), the password changed, the old session still 200 · #3 clearing cookie with `Path=/` → the jar keeps the real cookie · #4 null sid → **500 and full rollback** (the assert; silent no-op without it) · §2.5 #1 no `http.cors` → preflight 401 · #2 registrations swapped → refresh preflight without `Allow-Credentials` · #3 `*` → startup refused.

**Verified by me:** a `Secure` cookie survives a curl jar over plain HTTP; `HttpOnly` lines hidden by `grep -v '^#'` *(hit)*.

**Mutation checks:** none (D10, D12, D14, D16).

---

## 8. Backlog at phase close (2026-10-12): everything still owed

### A. Test debt (⏰ raised now: scheduling is your call)

| Owed | What the tests must prove | Decision |
|---|---|---|
| §1.3 registration | Duplicate email/username 409 with the field, also under a race; validation 400 never echoes the password | D9 |
| §1.4 verification | Token single use (conditional `UPDATE`), purpose-bound, expiry, resend always 202, revoke on reissue | D9 |
| §1.5 login, lockout, history | **The rollback trap** (counter survives a failed login); 5 → locked → correct password still 401; unlock after the duration (adjustable `Clock`); identical bodies for unknown/wrong/locked | D9 |
| §1.6 reset, change | Reset single use under a 20-way race; unknown email 202 with no email; change counts wrong guesses; reset unlocks + verifies | D9 |
| §2.1 tokens at login | Claims (no email), `exp` exact, cookie attributes, `no-store`, no session on failure, **`prod` without a key refuses to start** (`ApplicationContextRunner`) | D10 |
| §2.2 bearer | **Revoked session → 401** (D5's core), expiry boundary, `alg: none` / wrong key / **wrong `aud`**, converter rejections, change-password with bearer | D12 |
| §2.3 refresh | Every `RefreshResult` branch, grace boundary, **the 20-way race**, `Origin`, dead-session rollback (no false reuse), role change | D14 |
| §2.4 sessions | IDOR 404, revoke-one/all, logout idempotent, **reset and change revoke the right sessions**, trailing slash | D16 |
| §2.5 CORS | `CorsProperties` rejects `*`/path/slash/empty; the preflight table; credentials only on `/auth/**` | D16 |

Plus: Phase 0's "never echo `rejectedValue`" test, and **mutation checks for all of it** (none since §1.2). Reset and change currently have **no automated test at all**.

### B. Open Definition-of-done items

- **`prod` without a signing key refuses to start**: implemented, never proven (the missing `EmailSender` may fail first). The `ApplicationContextRunner` test above closes it.
- **README**: still describes HTTP Basic (6 mentions). Needs login, refresh, logout, the cookie, bearer usage, curl jars, Postman notes.

### C. Measurements owed

- Login timing, known vs unknown email (Phase 1) · bcrypt cost 10 vs 12 (Phase 1) · the §1.3 registration race (Phase 1) · the per-request session lookup's plan with real rows (expect the primary key).
- *Measured 2026-10-12:* suite 83 tests, 12 s, 2 containers.

### D. Process

- **Commit §2.4 + §2.5** (uncommitted at close).
- **Your 15-minute notes** for Phase 1 and Phase 2 (both templates still empty).
- **OpenAPI** (was "after Phase 2": now due): `/v3/api-docs` and `/swagger-ui` permitted in dev only, bearer scheme documented once.

### E. Nits, still open in the code (checked 2026-10-12)

- `LoginService`: three `// NEW` markers.
- `JwtKeyConfig`: the ephemeral key is chosen for "`dev` or `test`"; it should also require `prod` to be absent. The class now builds the decoder too (`JwtConfig`?).
- `UserSessionRepository`: `revokeAll` / `revokeAllExcept` lack the `expiresAt > :now` condition the other revokes have.
- `SessionService.refresh`: relies on having no caller transaction for its rollback-only undo (`REQUIRES_NEW` would enforce it).
- `CorsProperties`: accepts uppercase hosts and default ports that browsers never send (silent mismatch).
- `ProblemDetails`: `Instant.now()` instead of the `Clock`.
- `PasswordWorkflow.changePassword`: `catch (Exception e)` + `instanceof` (carried from Phase 1).
- `TestLogins.accessTokenFor`: raw `Map`.
- Refresh's 401 has no `WWW-Authenticate` (no standard cookie scheme; defensible).
- An orphan session if token issuing throws after `start()` (accepted).
- Phase 1 leftovers: unused `setRole` / `setTimezone` in `UserAccount`; the 403 detail "Email Not Verified"; `markEmailVerified` overwrites; the duplicated `uk_user_tokens_active` literal; no `/forgot-password` link in the "password changed" email; the CSRF/TRACE comment nits; `Organization`'s public no-arg constructor.
- *Closed:* `findAccountClaims`' comment is accurate since D13; the null-sid debt (asserted).

### F. Scheduled in later phases

- **Phase 3:** §1.7/§2.6 profile (side task, D17).
- **Phase 9:** async email sending (removes most timing leaks) · a real prod `EmailSender` (**prod refuses to start until then**) · the cleanup job (expired tokens and sessions, consumed refresh tokens, 12-month-old events).
- **Phase 10:** rate limiting (login, refresh, reset/resend email bombing, lockout DoS).
- **Phase 11:** forwarded headers behind a proxy · keys from orchestrator secrets (single-line PEM) · the profile-less deploy running as `dev` (`spring.profiles.active: dev` default).
- **Not built, by design (Phase 2's "Explicitly NOT"):** JWKS endpoint, multi-key verification, emailing the owner on reuse, a per-user session cap, DPoP, JWE.

### G. Project backlog (`PROJECT_CONTEXT.md` §6, unchanged)

Phase 10 API keys (committed) · bulk operations · attachments · CSV export · email change (design written) · saved filters, dashboards, issue links, custom fields, invitation emails, sprints, SLA, notification preferences.
