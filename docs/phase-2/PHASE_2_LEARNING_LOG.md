# Phase 2 — Learning Log (JWT & sessions)

> Organised by **theme**, not by sub-section (rule since 2026-10-01). Sub-section detail (briefs, requirements, per-step deliberate failures, test plans) lives in `PHASE_2_REQUIREMENTS.md`.
> Companions: `../phase-1/PHASE_1_LEARNING_LOG.md` (the identity this phase builds on) · `../phase-1/SECURITY_TESTING_GUIDE.md`.
> **Covers:** §2.1 (2026-10-07) · §2.2 (2026-10-09). Updated after every sub-section.

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
| **Client IP parsing** | Moved from `SecurityEventRecorder` into `ClientInfo.inetAddress()`, shared by events and sessions. | `ClientInfo` |

**Not yet built:** §2.3–§2.6 (no refresh yet: after an access token expires or the dev key changes, the client must log in again). **Tests:** none for §2.1 (D10); for §2.2 only the migration and 5 integration tests (D12).

**Commits:** `9b10252` (§2.1) · §2.2 not yet committed at wrap-up.

### Evidence

| | |
|---|---|
| **Verified by me** | V5 + V6 in a rolled-back transaction on the dev DB (2026-10-01: every constraint, the partial unique index, the 1-then-0 conditional consume, the cascade, the plans) · V5 as committed matches the brief (indentation only) · curl 8.7.1 keeps a `Secure` cookie over `http://localhost` (throwaway server, 2026-10-07) · the framework behaviour marked *(verified)* below, from 6.5.11 / Hibernate 6.6.53 sources · the code, by reading (review 2026-10-06) |
| **Reported by you** | Login body and cookie; the decoded token (header `kid ephemeral-…`, `RS256`; claims as agreed, `exp − iat = 900`, no email); no session on a wrong password or an unverified login; the ephemeral-key `WARN`; deliberate failures 1–4 "working as expected" (details, and which error the `prod` run showed, not sent) |
| **Reported by you (§2.2, 2026-10-09)** | All 14 rows of the run table "working as expected": bearer 200 · no token / Basic → 401 `AUTHENTICATION_REQUIRED` · `createdBy` = username · 403 `ACCESS_DENIED` bodies · expired token (1-min TTL) → 401 · `alg: none` → 401 · session revoked by SQL → 401 at once · expired header ignored on `/auth/login` · change-password with a bearer token · login history · restart → 401 · deliberate failures 1–5 · suite green (count, time and containers **not reported**) |
| **Verified by me (§2.2 review)** | `createDefaultWithValidators` varargs exists and skips its default timestamp validator when it finds yours at the top level · `JwtTimestampValidator` passes a token with **no** `exp` · `Jackson2ObjectMapperBuilder` adds Spring's `ProblemDetail` mixin · `withPublicKey(...)` uses a `SingleKeyJWSKeySelector` (the `kid` isn't consulted) |
| **Not proven** | That `prod` without a key refuses to start **because of the key** (the missing `EmailSender` may fail first); D5's revoked-session 401 and the expiry boundary by any automated test |

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

**Not answerable yet:** the multi-tab refresh race (§2.3) · CORS vs CSRF in practice (§2.3/§2.5) · log out everywhere, demonstrated (§2.4).

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

**Verified by me:** a `Secure` cookie survives a curl jar over plain HTTP; `HttpOnly` lines hidden by `grep -v '^#'` *(hit)*.

**Mutation checks:** none (D10, D12).

---

## 8. Carried debt

**Tests owed:** §1.3–§1.6 (D9) · **§2.1** (D10; including the `ApplicationContextRunner` check that `prod` refuses to start without a key) · **§2.2** beyond the migration (D12: revoked-session 401, expiry boundary, `alg: none` / wrong key / wrong `aud`, converter rejections, change-password with bearer, no `Set-Cookie`). All raised when Phase 2 closes.

**Measurements owed:** suite count, time and container count after §2.2 (Phase 1 ended at 76 tests, 2 containers) · the per-request session lookup's plan with real rows (expect the primary key).

**Before §2.4:** `revokeAllExcept` must refuse a null session id (`id <> NULL` revokes nothing).

**Nits from the §2.1 review:**
- `// NEW` markers in `LoginService`.
- The ephemeral-key check should also require `prod` to be absent.
- `revokeAll` / `revokeAllExcept` lack the `expiresAt > :now` condition that `revoke` / `revokeOwned` have; pick one rule.
- An orphan session (with a refresh token nobody holds) if token issuing throws after `start()`: accepted.
- Single-line PEM in env vars: Phase 11.
- *(§2.2)* `TestLogins.accessTokenFor` uses a raw `Map` · `JwtKeyConfig` now builds the decoder too (`JwtConfig`?) · `ProblemDetails` uses `Instant.now()`, not the `Clock` · the carried `catch (Exception e)` + `instanceof` in `changePassword`.

**Scheduled elsewhere:** Phase 9 cleanup job (expired sessions, consumed refresh tokens) · Phase 10 rate limiting on login/refresh · Phase 11 key loading from orchestrator secrets.
