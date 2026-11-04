# Phase 2 — JWT & Sessions (Summary)

> **One page for the whole phase:** what was built, what it taught, what was decided, and what to say about it in an interview. Built 2026-10-02 → 2026-10-12.
> **Where the detail lives:** the brief for each sub-section with its traps and deliberate failures ([PHASE_2_REQUIREMENTS.md](PHASE_2_REQUIREMENTS.md)), the full record of lessons by theme with all 26 interview questions and the backlog at close ([PHASE_2_LEARNING_LOG.md](PHASE_2_LEARNING_LOG.md)), the rebuild recipe and framework facts ([PHASE_2_IMPLEMENTATION_REFERENCE.md](PHASE_2_IMPLEMENTATION_REFERENCE.md)), the theory with spoken answers ([JWT_AND_SESSIONS_GUIDE.md](JWT_AND_SESSIONS_GUIDE.md)), and the topic pages in [`knowledge/`](../knowledge/README.md). This summary links to them and doesn't replace them.

---

## 1. The phase in one paragraph

Phase 2 made a login something the server can **end**. HTTP Basic (the password on every request) was deleted. Login now returns a short-lived signed **access token** (a JWT, 15 minutes) and sets a long-lived **refresh token** in an `HttpOnly` cookie. Both belong to a **session** row in the database. Every request checks that session, so logging out, logging out everywhere, a password reset, or a detected theft throws the other holder out **on their next request**. Refresh tokens rotate on every use; presenting an old one revokes the whole session, which is how a stolen token gets caught without any monitoring. The phase also put the filter chain's 401/403 into the same `ProblemDetail` format as everything else, added an `Origin` check and CORS for the two cookie endpoints, and closed without its own tests (they were paid in Phase 3 §3.0).

The phase's main lesson: **judge every design by what happens to the attacker's copy of a token, not the user's.** Deleting a token on the client logs out the honest user and nobody else.

---

## 2. What we built

| § | Built | Key classes | Changed later | Mechanisms explained in |
|---|---|---|---|---|
| 2.1 | Signing keys (RS256 from config, checked at startup; a throwaway key only in dev/test), access tokens with no email, sessions + refresh tokens (V5), login issuing both | `JwtProperties`, `JwtKeyConfig`, `AccessTokenIssuer`, `UserSession`, `RefreshToken`, `SessionService.start`, `RefreshCookie` | Phase 3 §3.0: "prod refuses to start without a key" finally proven by a test | [security 04](../knowledge/security/04-jwt-refresh-and-sessions.md), [spring 01](../knowledge/spring/01-boot-configuration-and-beans.md) |
| 2.2 | Bearer authentication on every request (Spring's resource server, our decoder and converter), the per-request session check, JSON 401/403, **Basic deleted** | `SessionJwtConverter`, `SessionStatus`, `AuthenticatedUser`, `TaskflowBearerTokenResolver`, `ProblemAuthenticationEntryPoint`, `ProblemAccessDeniedHandler` | Phase 3 §3.2: change-password's password re-check moved into `Reauthentication` | [security 04](../knowledge/security/04-jwt-refresh-and-sessions.md), [security 01](../knowledge/security/01-filter-chain-and-access-rules.md) |
| 2.3 | Refresh with rotation, reuse detection (10 s grace), the `Origin` check, three new security events (V6) | `SessionService.refresh`, `RefreshResult`, `SessionWorkflow`, `AllowedOrigins` | — | [security 04](../knowledge/security/04-jwt-refresh-and-sessions.md), [jpa 03](../knowledge/jpa/03-transactions.md), [jpa 05](../knowledge/jpa/05-bulk-updates-and-concurrency.md) |
| 2.4 | Logout, the session list, revoke one device, log out everywhere; reset ends every session, change ends the others | `SessionController`, `SessionService` (`revokeOwned`, `revokeAll`, `revokeAllExcept`) | — | [security 04](../knowledge/security/04-jwt-refresh-and-sessions.md), [jpa 03](../knowledge/jpa/03-transactions.md) |
| 2.5 | CORS inside the security chain; credentials only on `/auth/**` | `TaskflowCorsConfiguration`, `CorsProperties` | — | [security 06](../knowledge/security/06-cors-and-csrf.md) |
| 2.6 | Profile | — | **Not built.** Moved to Phase 3 §3.5 (D17) | — |

**Migrations:** V5 (`user_sessions`, `refresh_tokens`), V6 (three event types), both frozen.

**Endpoints:**

| Endpoint | Behaviour |
|---|---|
| `POST /auth/login` | 200 with the access token in the body and the refresh token in a cookie |
| `POST /auth/refresh` | 200 with a new access token and a rotated cookie; 401 `INVALID_REFRESH_TOKEN` for every failure |
| `POST /auth/logout` | Always 204, clears the cookie |
| `GET /users/me/sessions` | 200, live sessions with a `current` flag |
| `DELETE /users/me/sessions/{id}` | 204; someone else's id → the same 404 as a random one |
| `DELETE /users/me/sessions` | 204, every session including this one |

Every other `/api/v1/**` call needs `Authorization: Bearer …`. The full tables (errors, CORS answers, configuration) are in [reference Part E](PHASE_2_IMPLEMENTATION_REFERENCE.md).

---

## 3. Concepts learned

**Tokens** → [security 04](../knowledge/security/04-jwt-refresh-and-sessions.md)
- **A JWT is signed, not encrypted.** Anyone holding it can read the payload; the signature only stops changes. So claims hold ids and the role, never the email.
- **Two credentials, two jobs.** The access token goes with every request, so it leaks most easily: short-lived and self-contained. The refresh token goes to one endpoint, rarely: long-lived but checked in the database every time, so it can be killed.
- **RS256, not HS256.** With a shared HMAC secret, anything that can verify a token can also mint one. With RSA only the issuer holds the private key.
- **`kid` + opaque refresh tokens make key rotation cheap.** A token signed by a removed key gets 401, the client refreshes, nobody logs in again.
- **JWT times are whole seconds.** Truncate `iat` before computing `exp`, or the `expiresIn` you return disagrees with the token.
- **A token signed by a key the server no longer holds is dead,** expired or not. The dev key is per process, so a restart logs every access token out.

**Revocation: "stateless" is a choice** → [security 04](../knowledge/security/04-jwt-refresh-and-sessions.md)
- **"Stateless" describes verification, and you can give it up on purpose.** Every token carries `sid`; each request does one primary-key lookup on the session after the signature and expiry checks. Revoking that row kills the access and refresh tokens of that login on their next use.
- **Every way of ending a login is one conditional `UPDATE` on one row**, with "not revoked yet" in its `WHERE` so a second revocation keeps the first reason.
- **D7's table:** reset → every session (takeover recovery); change → every session except this one; lockout → none (otherwise five bad guesses log the owner out everywhere); log out everywhere → all, this one included.

**Rotation, reuse and races** → [security 04](../knowledge/security/04-jwt-refresh-and-sessions.md), [jpa 05](../knowledge/jpa/05-bulk-updates-and-concurrency.md)
- **Rotation:** each refresh consumes the token and issues a successor in the same family (the session).
- **Reuse detection:** a consumed token presented again proves a copy exists. The server can't tell which copy is the thief's, so the whole session dies and `REFRESH_TOKEN_REUSED` is recorded.
- **The grace window (10 s):** tabs sharing one cookie refresh together when a laptop wakes. A token consumed that recently is "superseded": rejected, nothing revoked, and the cookie isn't cleared (the browser already has the winner's).
- **One conditional `UPDATE` picks the single winner** of N concurrent refreshes. The losers wait on the row lock, re-check `consumed_at IS NULL`, and match nothing. A partial unique index guarantees one live token per session whatever the code does.
- **After losing, re-read with a scalar query.** The entity loaded before the race still says "not consumed".
- **Expired ≠ reused.** Only a consumed token signals theft.

**Transactions under rejection** → [jpa 03](../knowledge/jpa/03-transactions.md)
- **A detection that must survive its own failure response is returned, not thrown.** An exception would roll back the revocation and the event.
- **Judge every write a rejection leaves behind.** A refresh that found its session dead used to commit the consume, so a client's later retry looked like theft. `setRollbackOnly()` + return undoes it without an exception, **but only in the outermost transaction**.
- **Revocation commits in the password's transaction or not at all** (`MANDATORY`). As a separate step it can fail after the new password committed, leaving the attacker in.
- **Login stays non-transactional.** A failed login must leave its counter and event behind; the session start has its own transaction, and the session is created before `LOGIN_SUCCEEDED` is recorded.

**Insert-only entities** → [jpa 05](../knowledge/jpa/05-bulk-updates-and-concurrency.md)
- **Hibernate writes the whole row**, so touching a session through its entity while another request revokes it would write `revoked_at = NULL` back.
- **`updatable = false` on every mutable column** makes that impossible; HQL bulk updates still write those columns (verified in Hibernate's source).
- **`id <> NULL` is unknown, not true.** `revokeAllExcept(…, null)` would revoke nothing, silently. Assert non-null first.

**How Spring's resource server works** → [security 04](../knowledge/security/04-jwt-refresh-and-sessions.md), [security 01](../knowledge/security/01-filter-chain-and-access-rules.md)
- **The request path:** `BearerTokenAuthenticationFilter` (where Basic's filter used to sit) → resolver → `JwtAuthenticationProvider` → decoder (signature first, then every validator) → our converter (claims, then the session check) → security context → access rules.
- **An invalid token is rejected before authorization, even on `permitAll` paths.** Our resolver ignores tokens on `/api/v1/auth/**`, so a stale header can't lock a client out of login and refresh.
- **`setJwtValidator(x)` replaces the defaults**, including the only expiry check. Use `createDefaultWithValidators` and pass your timestamp validator at the top level.
- **A token with no `exp` passes the timestamp validator.** Require `exp` explicitly.
- **The filter has its own entry point**: set ours in `oauth2ResourceServer` and `exceptionHandling`, or half the 401s keep Spring's body.
- **A `Converter` bean ends up in MVC's conversion service**, so the JWT converter is a plain object.
- **Every bearer request publishes `AuthenticationSuccessEvent`.** The lockout listener reacts only to password logins, or an open tab would reset the counter while someone guesses.
- **401 vs 403 with bearer tokens:** 401 + `WWW-Authenticate: Bearer` (with `error="invalid_token"` when one was presented) tells the client to refresh or log in; 403 means "known, and no".

**The principal** → [security 02](../knowledge/security/02-password-authentication.md), [jpa 07](../knowledge/jpa/07-auditing-and-time.md)
- **Everything that reads the principal follows its type.** `@AuthenticationPrincipal(errorOnInvalidType = true)` fails loudly; the auditor fails **silently** (`created_by = system`).
- **The bearer principal has no email**, so re-authentication loads it by id. Never add personal data to the token to save a query.

**Cookies, CSRF and CORS** → [security 06](../knowledge/security/06-cors-and-csrf.md)
- **Three different things.** A cookie is attached by the browser automatically, which makes CSRF possible. CORS decides whether a script on another origin may *read* the response. `SameSite=Strict` stops the browser attaching the cookie cross-site.
- **The refresh cookie:** `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`. XSS can't read it, and it only travels to the auth endpoints.
- **`SameSite` stops other sites, not sibling subdomains.** The `Origin` allow-list closes that, checked **before** the cookie is read so a foreign page can't burn the token. An absent `Origin` is allowed (non-browser clients send none).
- **A cookie's identity is name + domain + path.** Clearing it needs the same name and path, which is why one class builds both.
- **CORS must run inside the security chain.** Preflights carry no credentials and would otherwise hit authentication and get 401. The first matching registration wins, so `/api/v1/auth/**` goes before `/api/v1/**`. Credentials with `*` are refused at startup.
- **`CorsFilter` rejects disallowed real requests too**, with a plain-text 403, before any of our code.

**Keys and configuration** → [spring 01](../knowledge/spring/01-boot-configuration-and-beans.md)
- **Fail closed on missing secrets, at startup.** The throwaway key is an allow-list (`dev`, `test`); any other profile refuses to start.
- **An RSA pair shares its modulus**, so comparing moduli proves the public key belongs to the private key at startup instead of at the first failed request.
- **Cross-field rules in the record's compact constructor; secrets redacted in `toString()`.**

---

## 4. Decisions

The decisions you'd have to defend. The full table (D1–D17 with options and dates) is in the [requirements](PHASE_2_REQUIREMENTS.md); all of them are indexed by topic in [decisions/INDEX.md](../knowledge/decisions/INDEX.md).

| Decision | Chosen | Why | What we turned down, and why |
|---|---|---|---|
| D1 Validating bearer tokens | Spring's resource server with our own decoder, converter and entry point | Header parsing, unsigned-token rejection, expiry and RFC 6750 are already written and reviewed | A hand-written filter: where `alg: none` bugs come from; a custom filter + provider is Phase 10's lesson |
| D2 Signing | RS256, `kid` from day one; throwaway key in dev/test; prod refuses to start without one | Whatever verifies can't mint; rotation logs nobody out | HS256: anything verifying can forge an admin token. ES256: as safe, more plumbing |
| D3 Lifetimes | Access 15 min · refresh 14 days idle · session 30 days absolute | Access lifetime only bounds a copied token; the cap stops a stolen family being rotated forever | 1 h / 90 days / no cap |
| D4 Refresh transport | `HttpOnly` cookie on `/api/v1/auth`; access token in the body, kept in memory | XSS can't read the long-lived credential | Both in the body (`localStorage`): XSS takes the 14-day token. Cost accepted: CSRF on two endpoints, credentialed CORS |
| D5 Revoking an access token | `sid` claim + one primary-key lookup per request | Every kind of logout acts on the next request; closes Phase 1's "reject tokens issued before the password change" | Pure stateless (wait for `exp`), an `iat` cutoff, a `jti` deny-list, opaque access tokens |
| D6 Reuse | Revoke the whole session + record an event; 10 s grace | Catches theft without monitoring; the grace stops multi-tab wake-ups logging users out | Strict detection (users logged out every morning); revoking all sessions; no detection |
| D7 Password events | Reset → all · change → all others · lockout → none | Reset is takeover recovery; lockout revoking would be a denial of service | Change ending this device too; revoking nothing |
| D8 Session list and revoke one | Built | The lost-laptop case, and the first IDOR rule (another user's id → 404) | Logout and log-out-everywhere only |
| D11 Insert-only session entities *(your approach)* | Mutable columns `updatable = false`; changes only by conditional bulk updates | A stray entity save **can't** resurrect a revoked session | The brief's weaker "touch with a conditional `UPDATE`" |
| JWT converter *(your call)* | A plain object, not a bean | Boot adds every `Converter` bean to MVC's conversion service | A `@Component` |
| `exp` required *(your call; the brief missed it)* | An explicit validator | A token without `exp` passes Spring's timestamp check | Trusting the default validator |
| D13 Dead session at refresh *(your approach)* | `setRollbackOnly()`, then return `SESSION_ENDED` | The token isn't burnt, so a retry isn't mistaken for theft; also covers the session dying mid-refresh | My fix (check the session before consuming): misses that race |
| Error format for the gate | One `ProblemDetail` factory for MVC and the filter chain; fixed details, never exception messages | One error shape; no parser messages leaking | Spring's defaults with `error_description` |
| D15 CORS rejection body | Spring's plain-text 403 accepted | Only browsers on a foreign origin get it, and they can't read it | Our own `CorsFilter` and processor for a body nobody reads |
| Tests (D9, D10, D12, D14, D16) | Deferred at each step, against the recommendation; the phase closed without them | Your call | Writing them per sub-section. Consequence: reset and change had **no automated test at all** until Phase 3 §3.0 |
| D17 §2.6 profile | Moved to Phase 3 | The phase rule: unfinished work becomes the next phase's side task | Extending the phase |

---

## 5. Patterns to reuse

1. **Judge security by the attacker's copy.** "What happens to a stolen token?" is the question every feature answers.
2. **Two credentials:** a short self-contained one for every request, a long revocable one for one endpoint.
3. **Revocation is one row**, checked on every request, changed only by conditional `UPDATE`s.
4. **One conditional statement decides a race;** branch on the row count, re-read with a scalar query, and back it with a unique index.
5. **Return, don't throw, when the failure must be saved;** `setRollbackOnly()` + return when a write must be undone without an error.
6. **`MANDATORY` for steps that must commit with something else** (revocation inside the password change, events with their revocation).
7. **Insert-only entities** for rows another request may change concurrently.
8. **Assert non-null before any `<>` on a parameter.**
9. **Add validators, never replace the defaults;** require `exp`.
10. **Fail closed at startup:** an allow-list of profiles that may run without a secret; check the key pair belongs together.
11. **Owner in the `WHERE`, and every miss the same 404** (IDOR).
12. **A rejection that must set a header is built in the controller**, not thrown (the clearing `Set-Cookie`).
13. **One builder for a cookie's set and clear.**
14. **Verify framework behaviour in the source**, not from memory (Part F of the reference is the list).

---

## 6. Bugs, traps and deliberate failures

**What happened to us.** Fewer silent bugs than Phase 1; most were caught by review or by your code.

| Bug | What happened | Rule now |
|---|---|---|
| The brief's validator plan missed tokens with no `exp` | Spring's timestamp check passes them; caught by your code | Require `exp` explicitly |
| A refresh that found its session dead still committed the consume (D13) | A client's later retry would have been reported as theft and revoked; caught in review (my brief had called the consume "harmless") | Judge every write a rejection leaves behind |
| `revokeAllExcept(account, null, …)` | Would revoke **nothing**, silently, because `id <> NULL` is unknown; caught in review (my brief predicted the opposite) | `Assert.notNull` before a `<>` |
| Grepping a curl cookie jar with `grep -v '^#'` | The `HttpOnly` cookie's line starts with `#HttpOnly_`, so it vanished and I reported "no cookie" | Don't grep jars for comments |
| Predicted `aud` as an array | A one-element audience is written as a plain string; Spring returns a list either way | Check, don't predict |
| Every test list in the phase skipped | Reset and change had no automated test; the most concurrency-sensitive code was proven only by manual runs | Phase 3 D1: a phase's own tests can't be deferred |

**Traps worth remembering**
- **"JWT is stateless, so logout can't work, so delete it on the client."** The attacker's copy keeps working.
- **Replacing the decoder's validators** instead of adding to them turns expiry off.
- **The default 60 s clock skew** keeps tokens alive a minute past `exp`; ours is 5 s on the injected clock.
- **Deleting consumed refresh tokens** makes reuse look like an unknown token: theft is never detected.
- **Two clock readings in one operation** drift apart; read once and pass it down.
- **Catching database errors in the session check** turns an outage into 401s.
- **Logging a built cookie or the response headers** leaks the raw refresh token.
- **Spring 6 doesn't match a trailing slash:** `DELETE /users/me/sessions/` is a 404, not "delete all".
- **Postman's cookie jar can't tell old tokens from new:** disable it and send `Cookie:` yourself; races need a terminal.

**Deliberate failures:** broken on purpose to see the error. 25 were run across the phase; the full list is in [learning log §7](PHASE_2_LEARNING_LOG.md).

| Broke | What you saw | Lesson |
|---|---|---|
| `email` claim added | The email readable by anyone with the token | Signed, not encrypted |
| Validator set to audience only | An expired token → 200 | Add validators, don't replace them |
| Resolver doesn't skip `/auth/**` | Login → 401 because of a stale header | Authentication runs before `permitAll` |
| Entry point set in one place | An empty 401 body | The filter has its own entry point |
| Auditor still on the old principal type | `created_by = system`, silently | Readers follow the principal's type |
| Session revoked by SQL | 401 on the very next request | D5 works |
| Check-then-act consume, 20 in parallel | Several 200s, then a 500 | One conditional `UPDATE` |
| Throw from the reuse branch | Session still live, no event | Return, don't throw |
| Grace 0, two tabs at once | Logged out after a "successful" refresh | Why the grace window exists |
| No owner in the revoke query | Alice logged out Bob's device | Owner in the `WHERE` |
| Revocation as a separate step after reset | 500, password changed, old session still worked | `MANDATORY`, same transaction |
| No `http.cors` | Preflight → 401 | CORS inside the chain |

---

## 7. Interview questions

Short answers, the way you'd say them. All 26 are in [learning log §5](PHASE_2_LEARNING_LOG.md); longer spoken answers are in [guide §11](JWT_AND_SESSIONS_GUIDE.md).

1. **Is a JWT encrypted?** "No, it's signed. Anyone with it can decode the payload; the signature only stops them changing it. So my tokens carry ids and the role, never the email."
2. **How do you log someone out if JWTs are stateless?** "Every access token carries a session id, and each request does one primary-key lookup on that session after the signature and expiry checks. Revoking the row kills that login's access and refresh tokens on their next use. The cost is that verification isn't purely stateless any more."
3. **Why have a refresh token at all?** "So the access token can be short-lived: a copied one is useful for 15 minutes. The refresh token lives longer, but it's checked in the database every time and kept in an `HttpOnly` cookie that JavaScript can't read."
4. **What is rotation, and how does it catch a thief?** "Each refresh token works once. A legitimate client never presents a used one, so when one comes back after the grace window, a copy exists. I can't tell which copy is the thief's, so I revoke the whole session and record the event."
5. **Two tabs refresh at the same moment with one cookie. What happens?** "Consuming is one conditional update. PostgreSQL makes the second wait and re-check, so exactly one wins. The loser re-reads the consumed time, sees it's milliseconds old, and gets a 401 without revoking anything or clearing the cookie."
6. **Why does the reuse branch return instead of throwing?** "Because the revocation and the event have to commit together with the 401. An exception inside the transaction would roll both back. I tried it: the session stayed live."
7. **RS256 or HS256?** "RS256. With HMAC the same secret signs and verifies, so any service that verifies tokens can mint an admin token. With RSA only the issuer has the private key."
8. **How do you rotate the signing key without logging anyone out?** "Every token has a `kid`. Sign with the new key, verify with both for one access-token lifetime, drop the old one. Refresh tokens are opaque database rows, so at worst a client refreshes once."
9. **Where do you keep the refresh token in a browser, and why not `localStorage`?** "In an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/api/v1/auth`. `localStorage` is readable by any script on the page, so one XSS steals a 14-day credential."
10. **Cookies bring CSRF back. How are refresh and logout protected?** "`SameSite=Strict` stops other sites. A sibling subdomain counts as same-site, so there's also an `Origin` allow-list, checked before the cookie is read."
11. **Why is an invalid token rejected even on a `permitAll` endpoint?** "Authentication runs before authorization. The bearer filter tries any token it finds. That's why my resolver ignores tokens on the auth endpoints, so a stale header can't lock someone out of login."
12. **Your custom validator could have accepted expired tokens. How?** "`setJwtValidator` replaces the defaults, including the only expiry check. And a token with no `exp` passes the timestamp validator anyway, so I require `exp` explicitly."
13. **A user changes their password. What happens to their devices?** "Every other session is revoked inside the password change's transaction, so the change and the revocation commit together. The device they used keeps working. A reset revokes all of them, because it's takeover recovery."
14. **Why 404, not 403, for someone else's session id?** "Ids are guessable. A 403 confirms the id exists. The owner is in the `WHERE`, so another user's id and a random one take the same code path and get the same 404."
15. **Why don't you update sessions through the entity?** "Hibernate writes every column on flush. If I touched a session while another request revoked it, my write would set `revoked_at` back to null. So the columns are non-updatable and every change is a conditional update."
16. **CORS vs CSRF, and why must CORS be in the security chain?** "CORS decides whether a script on another origin may read the response; CSRF is a forged request riding on a cookie. Preflights carry no credentials, so outside the chain they'd hit authentication and get 401."

---

## 8. Numbers measured

| What | Result |
|---|---|
| Test suite at phase close | **83 tests**, 12 s, 2 containers (Phase 1 ended at 76). Only §2.2's migration and 5 integration tests were new |
| Refresh race (manual) | 20 parallel refreshes with one cookie → exactly one 200, no 500. Automated in Phase 3 §3.0 |
| Token | `exp − iat = 900` s; no email; header `RS256` + `kid` |
| Mutation checks | **None** in Phase 2 (no tests to check). Phase 3 §3.0 caught 14 of 14 on the Tier-1 tests |
| Not measured | The per-request session lookup's query plan with real rows (expected: primary key); login timing known vs unknown email (Phase 1) |

---

## 9. Deferred and carried debt

Where each item went. Status checked against the code on 2026-11-02.

| Item | Where it went |
|---|---|
| Tests for the revoked-session 401, rotation, reuse, superseded, the 20-way race, the dead-session rollback, reset/change revocation, session IDOR, the trailing slash | ✅ Phase 3 §3.0 (Tier 1), all mutation-checked |
| `prod` without a key refuses to start | ✅ Proven by `JwtKeyConfigTest` (Phase 3 §3.0), including a `staging` profile that proves it's an allow-list |
| Tests for claim contents, cookie attributes, `no-store`, `alg: none`, wrong key, **wrong `aud`** (never exercised), the expiry boundary, the converter's rejections, `CorsProperties` and the preflight table | ⏸️ Tier 2 → Phase 11 |
| README still describes HTTP Basic | ⏸️ Phase 3 §3.5 (checked: still says Basic) |
| OpenAPI | ⏸️ Phase 11 |
| Measurements: the session lookup's plan; Phase 1's timing, bcrypt cost and registration race | Still owed |
| `TestLogins.accessTokenFor`'s raw `Map` | ✅ Fixed in Phase 3 §3.0 |
| `changePassword`'s `catch (Exception)` + `instanceof` | ✅ Gone (Phase 3 §3.2, `Reauthentication`) |
| Three `// NEW` markers in `LoginService` | Open |
| `JwtKeyConfig` allows the throwaway key for `dev` or `test` without also requiring `prod` to be absent (`prod,dev` would get one) | Open |
| `revokeAll` / `revokeAllExcept` lack the `expiresAt > :now` condition the other revokes have | Open |
| `SessionService.refresh` is plain `@Transactional`; its rollback-only undo relies on having no caller transaction (`REQUIRES_NEW` would enforce it) | Open |
| `CorsProperties` accepts uppercase hosts and default ports that browsers never send | Open |
| `ProblemDetails` uses `Instant.now()` instead of the `Clock` | Open |
| The `uk_user_tokens_active` name is written in two places | Open |
| The "password changed" email has no link to reset the password | Open |
| Accepted | Refresh's 401 has no `WWW-Authenticate`; an orphan session if issuing the token throws after the session starts; the CORS plain-text 403 (D15) |
| A real `prod` email sender, async sending, cleanup of expired sessions and consumed tokens | ⏸️ Phase 9 |
| Rate limiting on login and refresh | ⏸️ Phase 10 |
| Forwarded headers, keys as single-line PEM, the `spring.profiles.active: dev` default (still in `application.yml`) | ⏸️ Phase 11 |
| Not built, by design | JWKS endpoint, multi-key verification, emailing the owner on reuse, a per-user session cap, DPoP, JWE |
| §2.6 profile | ⏸️ Phase 3 §3.5 |
| Your 15-minute Phase 2 notes | Template at the end of the requirements doc is still empty |

---

## 10. Read more

- **Rebuild a feature:** [reference Part D](PHASE_2_IMPLEMENTATION_REFERENCE.md) (step-by-step recipe) and [features/INDEX.md](../knowledge/features/INDEX.md) (Phase 2 rows → reference C1–C13)
- **The theory, end to end:** [JWT_AND_SESSIONS_GUIDE.md](JWT_AND_SESSIONS_GUIDE.md) (the lifecycle in §7, traps in §10, spoken answers in §11)
- **Framework behaviour we relied on, checked in the source:** [reference Part F](PHASE_2_IMPLEMENTATION_REFERENCE.md)
- **Why we decided X:** [decisions/INDEX.md](../knowledge/decisions/INDEX.md)
- **A mechanism in depth:** [security 04](../knowledge/security/04-jwt-refresh-and-sessions.md), [security 06](../knowledge/security/06-cors-and-csrf.md), [knowledge/README.md](../knowledge/README.md)
- **Commands** (login with a curl jar, decoding a token, an `alg: none` token, a scratch copy + scratch database, SQL for sessions): [learning log §6](PHASE_2_LEARNING_LOG.md)
- **The tests that later proved this phase:** [SECURITY_TESTING_GUIDE.md](../phase-1/SECURITY_TESTING_GUIDE.md) §8 and [testing 02](../knowledge/testing/02-security-race-and-time-tests.md)
- **What it builds on:** [Phase 1 summary](../phase-1/PHASE_1_SUMMARY.md) · [Phase 0 summary](../phase-0/PHASE_0_SUMMARY.md)
