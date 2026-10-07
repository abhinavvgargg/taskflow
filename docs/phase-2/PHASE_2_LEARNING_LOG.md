# Phase 2 — Learning Log (JWT & sessions)

> Organised by **theme**, not by sub-section (rule since 2026-10-01). Sub-section detail (briefs, requirements, per-step deliberate failures, test plans) lives in `PHASE_2_REQUIREMENTS.md`.
> Companions: `../phase-1/PHASE_1_LEARNING_LOG.md` (the identity this phase builds on) · `../phase-1/SECURITY_TESTING_GUIDE.md`.
> **Covers:** §2.1 (2026-10-07). Updated after every sub-section.

---

## 1. What was built

| Capability | How it works | Key pieces |
|---|---|---|
| **Signing keys** | RS256 key pair from typed config (PEM), checked: public key must share the private key's modulus, at least 2048 bits. `dev`/`test` without a key generate an ephemeral pair and log one `WARN`; any other profile refuses to start. | `JwtProperties`, `JwtKeyConfig`, `JwtSigningKey` |
| **Access tokens** | `NimbusJwtEncoder`, header `RS256` + `kid` + `typ JWT`; claims `iss, aud, sub` (user id), `sid, preferred_username, role, iat, exp`. **No email.** `iat` truncated to whole seconds, `exp = iat + 15 min`. | `AccessTokenIssuer`, `IssuedAccessToken`, `TaskflowClaims` |
| **Sessions** | V5: `user_sessions` (the family: 30-day absolute expiry, revocation switch) + `refresh_tokens` (SHA-256 hash, 14-day idle expiry capped at the session's end, one unconsumed token per session by partial unique index). Insert-only entities; every change is a conditional `@Modifying` update. | V5, `UserSession`, `RefreshToken`, repositories, `SessionService.start` |
| **Login** | Authenticate (unchanged) → start session → record `LOGIN_SUCCEEDED` → issue token. Body `{accessToken, tokenType, expiresIn, user}`; refresh token in an `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` cookie. | `LoginService`, `AuthController`, `RefreshCookie`, `LoginResponse`, `LoginResult` |
| **Client IP parsing** | Moved from `SecurityEventRecorder` into `ClientInfo.inetAddress()`, shared by events and sessions. | `ClientInfo` |

**Not yet built:** §2.2–§2.6. Nothing accepts the access token yet. **No tests for §2.1** (D10).

### Evidence

| | |
|---|---|
| **Verified by me** | V5 + V6 in a rolled-back transaction on the dev DB (2026-10-01: every constraint, the partial unique index, the 1-then-0 conditional consume, the cascade, the plans) · V5 as committed matches the brief (indentation only) · curl 8.7.1 keeps a `Secure` cookie over `http://localhost` (throwaway server, 2026-10-07) · the framework behaviour marked *(verified)* below, from 6.5.11 / Hibernate 6.6.53 sources · the code, by reading (review 2026-10-06) |
| **Reported by you** | Login body and cookie; the decoded token (header `kid ephemeral-…`, `RS256`; claims as agreed, `exp − iat = 900`, no email); no session on a wrong password or an unverified login; the ephemeral-key `WARN`; deliberate failures 1–4 "working as expected" (details, and which error the `prod` run showed, not sent) |
| **Not proven** | That `prod` without a key refuses to start **because of the key** (the missing `EmailSender` may fail first); anything automated |

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

### Spring Security resource server (verified in 6.5.11 sources for the brief; used from §2.2)

- `setJwtValidator(x)` **replaces** the default validator, the only one checking `exp`; use `JwtValidators.createDefaultWithValidators`.
- `JwtTimestampValidator` uses `Clock.systemUTC()` unless given a clock; default skew 60 s.
- Validators don't short-circuit; the converter runs only after decoding and validation succeed.
- An invalid bearer token is rejected **before** authorization, even on `permitAll` paths.
- The bearer filter has its own entry point (set by `OAuth2ResourceServerConfigurer`); `exceptionHandling` alone leaves half the 401s with Spring's body.
- Every bearer request publishes `AuthenticationSuccessEvent` (`HttpSecurity`'s manager has Boot's publisher).
- `alg: none` → `PlainJWT` → rejected before validation. A malformed `Bearer` header → 401 `invalid_token`; several tokens → 400 `invalid_request`.
- spring-security-test's `jwt()` always builds `JwtAuthenticationToken`.
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

**Not answerable yet:** filter-by-filter path of a bearer request (§2.2) · how you revoke a JWT, demonstrated (§2.2/§2.4) · the multi-tab refresh race (§2.3) · CORS vs CSRF in practice (§2.3/§2.5).

---

## 6. Commands

```bash
./mvnw spring-boot:run                                                     # dev: look for the ephemeral-key WARN
curl -s -i -c /tmp/tf.jar -H 'Content-Type: application/json' -d '{"email":"<email>","password":"<pw>"}' localhost:8080/api/v1/auth/login
cat /tmp/tf.jar                                                            # the cookie line starts with #HttpOnly_localhost
# decode header and payload of a JWT
python3 -c 'import sys,base64,json;[print(json.dumps(json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4))),indent=2)) for p in sys.argv[1].split(".")[:2]]' "<accessToken>"
# prod without a key (needs the DB variables; the missing EmailSender may fail first)
DB_HOST=localhost DB_PORT=5432 DB_NAME=taskflow DB_USER=taskflow DB_PASSWORD=taskflow_local_dev FRONTEND_BASE_URL=http://localhost:3000 ./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

```sql
select id, user_account_id, created_at, expires_at, last_refreshed_at, revoked_at, revoke_reason, host(ip_address) ip, user_agent from user_sessions order by id desc limit 5;
select id, session_id, left(token_hash, 12) hash, created_at, expires_at, consumed_at from refresh_tokens order by id desc limit 5;
```

---

## 7. Deliberate failures and mutation checks

**Seen** (reported by you, 2026-10-07, details not sent): §2.1 #1 email claim readable · #2 unmasked result logs the raw token · #3 second unconsumed token refused by `uk_refresh_tokens_active` · #4 `prod` without a key refuses to start (which error appeared first: not reported).

**Verified by me:** a `Secure` cookie survives a curl jar over plain HTTP; `HttpOnly` lines hidden by `grep -v '^#'` *(hit)*.

**Mutation checks:** none (no §2.1 tests, D10).

---

## 8. Carried debt

**Tests owed:** §1.3–§1.6 (D9) · **§2.1** (D10; the list is in §2.1's *Tests*, including the `ApplicationContextRunner` check that `prod` refuses to start without a key). Both raised when Phase 2 closes.

**Before §2.4:** `revokeAllExcept` must refuse a null session id (`id <> NULL` revokes nothing).

**Nits from the §2.1 review:**
- `// NEW` markers in `LoginService`.
- The ephemeral-key check should also require `prod` to be absent.
- `revokeAll` / `revokeAllExcept` lack the `expiresAt > :now` condition that `revoke` / `revokeOwned` have; pick one rule.
- An orphan session (with a refresh token nobody holds) if token issuing throws after `start()`: accepted.
- Single-line PEM in env vars: Phase 11.

**Scheduled elsewhere:** Phase 9 cleanup job (expired sessions, consumed refresh tokens) · Phase 10 rate limiting on login/refresh · Phase 11 key loading from orchestrator secrets.
