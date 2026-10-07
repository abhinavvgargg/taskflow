# Phase 2 — JWT & Sessions (Requirements)

> Companion to `../PROJECT_CONTEXT.md` · what Phase 1 built and learned: `../phase-1/PHASE_1_LEARNING_LOG.md` · test patterns: `../phase-0/TESTING_GUIDE.md` and `../phase-1/SECURITY_TESTING_GUIDE.md`. Requirements only, no code (the migration SQL is provided, as agreed). Tick the boxes as you go.
> **Time-box: 7h, tests included. Hard stop at 10.5h (150%).** The Phase 1 test debt isn't in it (D9: raised again when Phase 2 closes). Anything unfinished becomes a side task in Phase 3.

## ▶ Where we are (resume here) — §2.1 done 2026-10-07, §2.2 next

| | |
|---|---|
| **Done** | Brief (2026-10-01) · decisions D1–D9 (2026-10-02) · **§2.1** signing keys, sessions, tokens at login: built, reviewed, run (2026-10-07). **Tests skipped** (D10). Not yet committed at wrap-up. |
| **Next** | **Implement §2.2** (bearer authentication, Basic removed, JSON 401/403). Then bring the code for review. Your 15-minute Phase 1 notes are still unwritten (bottom of `PHASE_1_REQUIREMENTS.md`). |
| **Decisions to raise** | None open for §2.2. ⏰ **When Phase 2 closes, remind the user of the test debt:** §1.3–§1.6 (D9) **and §2.1** (D10); the planned lists are in each section's *Tests*. Also before §2.4: `revokeAllExcept` must refuse a null session id (review 2026-10-06). |
| **Carried in** | See *Carried in from Phase 1*: filter-chain 401/403 as `ProblemDetail`, "reject tokens issued before `password_changed_at`" (closed by D5 + D7), §1.7 profile (§2.6), the §1.3–§1.6 tests (D9: deferred, raised at Phase 2's close), OpenAPI (after this phase). |
| **Environment state** | Dev DB at **V5** (V1–V5 frozen; V6 not yet added). Sessions and refresh tokens exist from the §2.1 runs (e.g. user 1802 `abhinavvgargg-jwt`, session 1952). The dev signing key is **ephemeral**: every restart invalidates access tokens. `pom.xml` has `spring-boot-starter-oauth2-resource-server`. `~/.m2` holds the 6.5.11 sources jars of the three `spring-security-oauth2-*` modules (fetched for verification). |
| **Numbering** | Decisions are numbered **D1–D9** in this doc; the Decisions table holds them with dates. |

---

**The goal of Phase 2 is a login that can be ended.** Phase 1 proved *who* you are on every request by sending the password every time. Phase 2 replaces that with a short-lived signed access token and a long-lived refresh token tied to a **session** the server can revoke: log out of this device, log out everywhere, and have a password reset throw an attacker out **on their next request**, not in 15 minutes.

⚠️ **Trap, the one that defines this phase:** "JWT means stateless, so logout can't work, so we just delete the token on the client." Deleting the token on the client logs out the honest user and nobody else. Every scenario that matters here is one where **someone else** holds a token: a stolen laptop, an XSS, a leaked log line. **Every section below is judged by what happens to the attacker's copy**, not the user's.

---

## Carried in from Phase 1

| Item | What happens in Phase 2 |
|---|---|
| Filter-chain 401/403 bypass `@RestControllerAdvice` (noted in §0.7, seen in §1.1) | **§2.2**: an `AuthenticationEntryPoint` and an `AccessDeniedHandler` write the same `ProblemDetail` as everything else. |
| "Reject tokens issued before `password_changed_at`" | **Closed by a stronger mechanism** (D5 + D7): a reset or change revokes the sessions themselves, and every request checks its session. No `iat` comparison needed. |
| HTTP Basic is a bridge (decision 1) | **Deleted in §2.2.** With it goes the "lockout through Basic" side door: per-request authentication never touches a password again. |
| §1.7 profile (decision 28) | **§2.6**, side task, first on the trim list. |
| §1.3–§1.6 tests owed | **D9: deferred; raised again when Phase 2 closes.** |
| OpenAPI | Still after Phase 2, so the bearer scheme is documented once. |
| `LogoutFilter` disabled | **Stays disabled.** Logout is an API endpoint with its own rules (§2.4), not Spring's form-logout. |
| The CSRF comment "runs two ideas together" (nit) | **§2.2** rewrites it: CSRF stays off for bearer requests, and the two cookie endpoints get their own protection (§2.3). |
| Nits in files you'll touch anyway | Fix them as you pass: the slice mocking the concrete `TaskflowUserDetailsService`, the unused import in `AuditAwareImpl`, `catch (Exception e)` + `instanceof` in `changePassword`. |

---

## 🏗️ Decisions — raised first (all decided 2026-10-02; see the Decisions table)

Each has options, a recommendation, and why the recommendation beats every alternative listed. Choose, or defer one to the start of the sub-phase that needs it.

### D1 — How bearer tokens are validated (needed by §2.1)

| Option | What it means |
|---|---|
| **A. Spring Security's resource server** *(recommended)* | `http.oauth2ResourceServer(o -> o.jwt(...))` with **your** `JwtDecoder` bean, **your** converter (Jwt → your `Authentication`), **your** entry point. Tokens are minted with Spring's `NimbusJwtEncoder`. Dependency: `spring-boot-starter-oauth2-resource-server`. |
| B. Your own `OncePerRequestFilter` + a JWT library | The plan's original wording ("own JWT filter"). You parse the header, verify, build the `Authentication`, decide what failures do. |
| C. Your own filter + your own `AuthenticationProvider` | B, but split the Spring way: the filter only extracts, a provider verifies. |

**Why A beats B and C.** Everything that's easy to get subtly wrong is already written and reviewed: header parsing (*verified*: a strict `^Bearer <token>$` pattern, **several tokens rejected**, query-parameter and form-body tokens **off** by default), unsigned tokens rejected (*verified*: a `PlainJWT` throws before validation), expiry with clock skew, RFC 6750's `WWW-Authenticate` header, mapping failures to 401 without swallowing outages. What you still write is exactly what's specific to TaskFlow: the keys, the claims, the session check, the principal, the error body.
- **B's concrete failure:** a hand-written parser is where `alg: none` and algorithm-confusion bugs historically come from (several JWT libraries shipped APIs that accepted unsigned tokens when called the "wrong" way). The failure is silent: every test that uses a real token passes. You'd also own "what does an expired token on a `permitAll` path do?" and "is a database outage a 401?" (Phase 1's lockout listener showed how a wrong answer hides).
- **B/C's learning overlap:** you already wrote a `OncePerRequestFilter` (`CorrelationIdFilter`). A custom filter + custom provider + custom `Authentication` is **Phase 10's** whole point (API keys, second chain). Doing it twice teaches it once and leaves two hand-written security filters to maintain.
- **A's honest costs:** "OAuth2" vocabulary for something that isn't OAuth (we're our own issuer; there's no authorization server, that's the microservices track). And you must **read** `BearerTokenAuthenticationFilter` to know where it sits and what it does. That's in §2.2's 🔍.
- If you choose A, `PROJECT_CONTEXT.md` §5's Phase 2 row ("Own JWT filter, `OncePerRequestFilter`") gets updated to match.

### D2 — Signing algorithm and keys (needed by §2.1)

| Option | What it means |
|---|---|
| **A. RS256 (RSA 2048), `kid` in the header from day one; dev and test generate an ephemeral key pair at startup; prod refuses to start without a configured key** *(recommended)* | The private key signs, the public key verifies. |
| B. HS256 with a 256-bit shared secret from an env var | One secret both signs and verifies. |
| C. ES256 (EC P-256) | Asymmetric like A; smaller and faster. |

**Why A beats B.** With HMAC, **whatever can verify a token can also mint one**. The day a second component verifies TaskFlow tokens (a gateway, a second service in the microservices track, a support tool), it holds a key that can mint an `ADMIN` token for any user. A leaked verification config is a full takeover. Also, HS256 secrets are often human-chosen, and a captured token can be brute-forced **offline** against a weak secret. With RSA, the public key can be published (a JWKS endpoint later) and is useless for forging.
**Why A over C.** Security-wise C is as good. The difference is plumbing: Spring's decoder builder takes an `RSAPublicKey` directly (*verified*: `NimbusJwtDecoder.withPublicKey(RSAPublicKey)`, `withSecretKey`, `withJwkSetUri`, `withIssuerLocation`; no EC shortcut). EC means building a JWK source yourself, for no security gain here.
**A's costs:** key loading code; tokens are larger (~340 extra bytes of signature); signing costs ~1 ms (verification is fast).
**The ephemeral dev key** means a restart invalidates every access token. That's deliberate: the client refreshes (refresh tokens are in the database, not signed), so you'll *see* the design work. **Never commit a private key, even a dev one**: secret scanners flag it, and dev config gets copied into prod config.
**Why `kid` now:** rotating the key later is "add the new key, sign with it, drop the old one". And because refresh tokens are opaque, rotation logs **nobody** out: an access token signed with a removed key gets a 401, the client refreshes, done. Multi-key verification is not built in Phase 2; the procedure is documented.

### D3 — Lifetimes (needed by §2.1)

| Option | Access token | Refresh token | Session (absolute) |
|---|---|---|---|
| **A** *(recommended)* | **15 min** | **14 days idle** (each refresh extends it) | **30 days** from login, then a password is required |
| B | 5 min | 14 days idle | 30 days |
| C | 1 h | 90 days | none |

**Why A.** With D5 (per-request session check), revocation is immediate whatever the access lifetime, so the access lifetime only bounds three things: how long a copied access token works while its session stays live, how stale the `role` claim can be, and how often rotation happens (each rotation is a chance to detect reuse). 15 minutes is the usual balance. **B** triples refresh traffic for little gain on those three. **C's concrete failure:** with no absolute cap, an attacker who stole a refresh token from a laptop the victim no longer uses can rotate it **forever**: reuse detection never fires, because the victim never presents the old token again. The 30-day cap kills every family, stolen or not.

### D4 — Where the refresh token travels (needed by §2.1)

| Option | What it means |
|---|---|
| **A. An `HttpOnly; Secure; SameSite=Strict` cookie with `Path=/api/v1/auth`. The access token goes in the JSON body and the client keeps it in memory** *(recommended)* | JavaScript can never read the refresh token. The browser sends it only to `/api/v1/auth/*`. |
| B. Both tokens in the JSON body | The client stores the refresh token itself (usually `localStorage`). |
| C. Backend-for-frontend (BFF) | A server component holds the tokens; the browser holds only a session cookie. |

**Why A beats B.** An XSS on the frontend can read anything JavaScript can read. With B, it reads the refresh token: **30 days** of access that survive closing the tab. **Concrete failure:** Phase 9 renders user content (comments, @mentions). One stored-XSS bug in the frontend, and every viewer's refresh token is posted to the attacker. With A, the same XSS can act inside the open page (nothing stops that), but it can't take the long-lived credential away.
**A's costs, named:** a cookie brings CSRF back for exactly the two endpoints that read it (handled in §2.3 by `SameSite=Strict`, the path, and an `Origin` check); CORS must allow credentials on `/api/v1/auth/**` (§2.5); `curl` needs a cookie jar; non-browser clients must handle cookies (machine integrations use Phase 10's API keys, not refresh tokens).
**Why not C.** It's the strongest browser model, but it's a separate server we'd have to build alongside a frontend that doesn't exist. Know it for interviews.

### D5 — Revoking an access token before it expires (needed by §2.2)

| Option | What it means |
|---|---|
| **A. Per-request session check: the token carries `sid`; every request does one primary-key lookup "is this session live?"** *(recommended)* | Logout, logout-all, password reset/change and reuse detection take effect on the **next request**. |
| B. Pure stateless: a token is valid until `exp` | No lookup. |
| C. User-level cutoff: reject tokens whose `iat` is before `password_changed_at` (or a `tokens_valid_after` column) | One lookup per request (the user row). |
| D. A deny-list of revoked `jti`s | One lookup per request, plus a table of revoked ids. |
| E. No JWT: an opaque access token looked up in the database | The classic server-side session, over a header. |

**Why A beats each alternative.**
- **B's concrete failure:** "log out everywhere" and a password reset leave every stolen access token working for up to 15 minutes, which is exactly the moment the user is reacting to a compromise. Phase 1 decision 26 emails the owner "your password was changed"; under B, the attacker keeps going after that email.
- **C** costs the same lookup as A and covers less: password events and logout-all, but not single-device logout, not revoking one session from the list, not reuse detection.
- **D** costs the same lookup, grows a table, and **can't do logout-all**: you'd need the `jti` of every outstanding token, which you only have if you store every token you issue.
- **E** is honestly close at this scale: equally revocable, simpler. A keeps two things E doesn't: forged or expired tokens are rejected **by signature and `exp` before touching the database**, and the claims carry id, username and role without loading the user (the lookup is one indexed row, not a user load). It also keeps the path to stateless verification open (drop the lookup) for the microservices track.

**A's honest cost:** verification is no longer stateless. A second service couldn't validate tokens without database access (it would need introspection). That's the interview answer: *"JWTs can't be revoked; you choose between waiting for `exp` and a lookup. I chose the lookup because…"*

### D6 — What reuse of a rotated refresh token does (needed by §2.3)

| Option | What it means |
|---|---|
| **A. Revoke the whole session (the family) and record `REFRESH_TOKEN_REUSED`; but a token consumed less than 10 s ago (configurable, 0 = off) is rejected *without* revoking** *(recommended)* | Theft is detected; a legitimate concurrent refresh isn't punished. |
| B. A without the grace window | Strict. |
| C. Revoke **all** the user's sessions | Paranoid. |
| D. No detection: just reject consumed tokens | |

**Why the grace window (A over B).** Several tabs share one cookie. After a laptop wakes from sleep, every tab's access token has expired and they all refresh **at the same moment with the same cookie**. Under B, the second request looks like theft: the family is revoked and the user is logged out of every tab, every morning. **A's cost:** if an attacker rotates a stolen token and the victim presents the old one **within 10 s**, it isn't detected (the victim's request fails, the attacker's family lives until its idle or absolute expiry). That's a narrow window to hit, and it's documented.
**Why not C.** The reuse proves one family's token leaked. The user's other devices hold independent credentials. Revoking everything turns every client bug that replays a token into "logged out on all devices".
**D's concrete failure:** the attacker rotates the stolen token; the victim's next refresh fails; the victim logs in again (a new session) and thinks nothing of it; the attacker's family lives on, undetected, until its 30-day cap.
*Reference: refresh-token rotation with reuse detection is the OAuth 2.0 Security BCP's (RFC 9700) recommendation for public clients. From memory, not re-checked against the RFC text.*

### D7 — What password events and lockout do to sessions (needed by §2.4)

| Option | Reset | Change | Lockout | Logout everywhere |
|---|---|---|---|---|
| **A** *(recommended)* | **all** sessions | **all other** sessions (keep the one that changed it) | **none** | all, including this one |
| B | all | all, including this one | none | all |
| C | none | none | none | all |

**Why A.** A reset is the **takeover-recovery path**: any existing session might be the attacker's. A change comes from a session that just re-proved the password through the `AuthenticationManager` (counted toward lockout), so killing it adds friction without security (B). **Lockout revokes nothing**, because five wrong guesses say nothing about existing sessions, and revoking would let anyone who knows your email log you out of everything with five bad passwords (Phase 1's lockout DoS, amplified). **C's concrete failure** is D5-B's: the "your password was changed" email arrives and the attacker's session carries on.

### D8 — The session list and "revoke one session" (needed by §2.4)

| Option | Endpoints |
|---|---|
| **A** *(recommended; first on the trim list after §2.6)* | `GET /users/me/sessions`, `DELETE /users/me/sessions/{id}`, `DELETE /users/me/sessions` (= log out everywhere), plus `POST /auth/logout` (this device) |
| B | Only `POST /auth/logout` and log-out-everywhere |

**Why A.** Once sessions exist, listing them is one query. It's the "sign out the laptop I lost" feature: under B, the only answer is "log out everywhere". And it's the first resource in the project that **belongs to a user and is addressed by id**, so it's the first real IDOR rule (someone else's session id → 404), which Phase 4 generalises. Cost: about 45 minutes.

### D9 — When the Phase 1 test debt gets paid

| Option | When |
|---|---|
| **A. One session right after §2.2, starting with the tests that guard security rules** *(recommended)* | Bearer authentication exists, so the tests are written once. Budgeted separately (~2h), outside Phase 2's 7h. |
| B. At the end of Phase 2 | |
| C. Phase 11 ("fill test gaps") | |

**Why A.** Integration tests written before §2.2 would authenticate with Basic and be rewritten a week later. After §2.2 they're written once, **and** they protect §2.4, which edits `PasswordService` (revoking sessions on reset and change). Order inside the session: the lockout rollback trap, the reset single-use race, login/resend/reset enumeration (identical bodies), then the rest of the planned lists. **B** loses that refactor safety for §2.4. **C's concrete failure:** four sub-sections of security code stay untested while Phases 3–8 edit around them, and the plan lists "testing at every phase" as must-not-cut.

---

## Decisions (D1–D9 decided 2026-10-02; later rows as dated)

| # | Decision | Chosen | Options considered · the reasoning |
|---|---|---|---|
| D1 | **How bearer tokens are validated**, decided 2026-10-02 | **Spring Security's resource server** (`oauth2ResourceServer().jwt()`) with our own `JwtDecoder`, converter and entry point; tokens minted with `NimbusJwtEncoder` | Options: resource server · own `OncePerRequestFilter` · own filter + provider. Header parsing, unsigned-token rejection, expiry and RFC 6750 handling are already written and reviewed; a custom filter + provider is Phase 10's lesson, and `CorrelationIdFilter` already taught `OncePerRequestFilter`. |
| D2 | **Signing algorithm and keys**, decided 2026-10-02 | **RS256 (RSA 2048), `kid` from day one; ephemeral key pair in dev/test; prod refuses to start without a key** | Options: RS256 · HS256 · ES256. With HMAC, anything that verifies can mint tokens; Spring's builder takes an `RSAPublicKey` directly (ES256 = more plumbing, no gain). Opaque refresh tokens make key rotation log nobody out. |
| D3 | **Lifetimes**, decided 2026-10-02 | **Access 15 min · refresh 14 days idle · session 30 days absolute** | Options: 15m/14d/30d · 5m/14d/30d · 1h/90d/no cap. With the per-request check, access lifetime only bounds a copied token, role staleness and rotation frequency; without an absolute cap a stolen family can be rotated forever. |
| D4 | **Refresh token transport**, decided 2026-10-02 | **`HttpOnly; Secure; SameSite=Strict` cookie, `Path=/api/v1/auth`; access token in the body, kept in memory** | Options: cookie · both in the body · BFF. An XSS can't read the long-lived credential. Costs accepted: CSRF on two endpoints (SameSite + `Origin` check), credentialed CORS on `/auth/**`, cookie jars in curl. |
| D5 | **Access-token revocation**, decided 2026-10-02 | **Per-request session check: `sid` claim, one primary-key lookup per request** | Options: session check · pure stateless · `iat` cutoff · `jti` deny-list · opaque access tokens. Logout, logout-all, reset/change and reuse detection act on the next request. Cost accepted: verification is no longer stateless. Closes Phase 1's "reject tokens issued before `password_changed_at`". |
| D6 | **Reuse detection**, decided 2026-10-02 | **Revoke the session (family), record `REFRESH_TOKEN_REUSED`; a token consumed < 10 s ago (configurable) is rejected without revoking** | Options: family + grace · strict · all sessions · none. The grace stops the multi-tab wake-up race logging users out. Cost accepted: an attacker rotating within 10 s of the victim isn't detected. |
| D7 | **Password events and sessions**, decided 2026-10-02 | **Reset → all sessions · change → all others (keep current) · lockout → none · log out everywhere → all, including this one** | Options: as chosen · change kills current too · nothing revoked. Reset is takeover recovery; lockout revoking would let five bad guesses log the owner out everywhere. |
| D8 | **Session list and revoke-one**, decided 2026-10-02 | **Built: `GET /users/me/sessions`, `DELETE /users/me/sessions/{id}`, `DELETE /users/me/sessions`, `POST /auth/logout`; first on the trim list after §2.6** | Options: build · logout and logout-all only. The lost-laptop case, and the first IDOR rule (another user's id → 404). |
| D9 | **Phase 1 test debt (§1.3–§1.6)**, decided 2026-10-02 | **Deferred: raise it again when Phase 2 closes** (your call, not the recommendation) | Options: right after §2.2 (recommended) · end of Phase 2 · Phase 11. Your call; no reason recorded. Cost accepted: §2.4 edits `PasswordService` (revocation on reset/change) without Phase 1's reset/change tests as a safety net, so §2.4's own integration tests must cover reset and change end to end. |
| D10 | **§2.1 tests**, decided 2026-10-07 | **Skipped for now; raised with D9 when Phase 2 closes** (your call) | The recommendation was to write them in §2.1 (the agreement lists testing at every phase as must-not-cut). Cost accepted: nothing automated proves the claims set, the cookie attributes, "no session on failed login", or that `prod` without a key refuses to start (the manual `prod` run can be masked by the missing `EmailSender`). §2.2 changes the security config, so those rules have no regression net while it does. |
| D11 | **Insert-only session entities**, your approach, 2026-10-06 | **Every mutable column of `UserSession` / `RefreshToken` is `updatable = false`; every change is a conditional `@Modifying` update** | Better than the brief, which only said "touch the session with a conditional UPDATE": a stray entity flush **can't** write `revoked_at = NULL` back, so the whole-row resurrection bug (§2.3) is impossible even by mistake. Verified in Hibernate 6.6.53's source: HQL `SET` doesn't check `updatable`, so the bulk updates still write (runtime proof comes with §2.3's tests). |

---

## Concepts you need first (the *why*, briefly)

💡 **A JWT is signed, not encrypted.** The compact form is `base64url(header).base64url(payload).signature`. Anyone holding the token can read the payload (`echo <middle part> | base64 -d`); only the signature stops them **changing** it. **Why it matters:** whatever you put in a claim ends up readable in every log, proxy and browser tool the token passes through. So: no email, no PII, no secrets in claims.

💡 **Access token vs refresh token.** The access token is sent on **every** request, so it's the one most likely to leak (logs, proxies, a debugging screenshot). Make it short-lived and self-contained. The refresh token is sent to **one** endpoint, rarely, so it can live long, and it's checked against the database every time it's used. Two tokens let you have both "fast to verify" and "can be killed".

💡 **Stateless vs stateful, honestly.** "Stateless" means the server can verify a token without remembering anything. That's a property of **verification**, and you can trade it away on purpose (D5). The refresh side is always stateful: rotation and revocation need a database. Interviewers probe whether you know which half is which.

💡 **Rotation and reuse detection.** Every refresh **consumes** the presented refresh token and issues a new one. All the tokens descending from one login form a **family** (here: a session). A consumed token should never be presented again. If it is, either a client bug or a **thief** holds a copy, and the server can't tell which copy is the thief's. So it kills the whole family. That's how a stolen refresh token gets detected *without* any monitoring.

💡 **Where the bearer filter sits.** With the resource server, `BearerTokenAuthenticationFilter` takes the slot just before `BasicAuthenticationFilter`'s (*verified* in `FilterOrderRegistration`): after the context-holder and headers filters, **before** `ExceptionTranslationFilter` and `AuthorizationFilter`. So it authenticates **before** any rule is checked, even for `permitAll()` paths (*verified*, §2.2's main trap). 🔍 Read its `doFilterInternal`: it's 40 lines.

💡 **401 with a bearer token.** RFC 6750 says a 401 for a bearer request carries `WWW-Authenticate: Bearer`, plus `error="invalid_token"` when a token was presented and rejected. A client uses that to decide "refresh and retry" vs "log in again".

💡 **Cookies, CSRF and CORS are three different things.** A **cookie** is attached by the browser automatically, which is what makes **CSRF** possible: another site makes the browser send a request that carries your cookie. **CORS** decides whether a script on another origin may **read** the response (and may send credentials); it doesn't stop the request reaching the server. `SameSite=Strict` stops the browser attaching the cookie to cross-site requests. **Bearer headers are never attached automatically**, which is why CSRF stays off for them.

---

## How each section is laid out

Same as Phase 1 (`PROJECT_CONTEXT.md` §3.1): what → how & why (choices, alternatives not taken) → optimising for → concepts → requirements (grouped by class, each group ending in **Done when**) → traps → deliberate failures → build order → tests.

---

## 2.1 — Signing keys, sessions, and tokens at login ✅ built 2026-10-07 (tests skipped, D10)

### What we're building

A successful login now **starts a session** and hands back two credentials: a signed **access token** (JWT, 15 min) in the JSON body, and an opaque **refresh token** in an `HttpOnly` cookie. The session and the refresh token are rows in two new tables (V5). The signing key and every lifetime come from typed config; prod refuses to start without a key.

- **In:** V5, the key configuration, the `JwtEncoder`, `AccessTokenIssuer`, the session and refresh-token entities, `SessionService.start`, the new login response and cookie.
- **Out:** *accepting* the access token (§2.2), refresh (§2.3), logout and revocation (§2.4), CORS (§2.5).

### How we're building it, and why

**The moving parts**

1. **`JwtProperties`** (`taskflow.security.jwt`): issuer, audience, access-token lifetime, clock skew, and the key (key id, private key, public key). Validated at startup.
2. **`JwtKeyConfig`** (`common/security`): produces the RSA key pair. From config when present; in `dev` and `test`, a fresh 2048-bit pair with a random `kid` and one `WARN` line saying so; in `prod` without a key, startup fails. ↺ D2
3. **`JwtEncoder`** bean: Spring's `NimbusJwtEncoder` over a key source holding the one key. ↺ D1
4. **`AccessTokenIssuer`** (`common/security`): takes user id, username, role, session id and "now"; returns the signed token and its expiry. It knows nothing about entities, so `common` still imports no feature.
5. **`SessionProperties`** (`taskflow.security.sessions`): refresh idle lifetime, absolute lifetime, reuse grace (§2.3), and the cookie's name, path, `Secure`, `SameSite`.
6. **`user.session` package**: `UserSession` and `RefreshToken` entities, their repositories, and `SessionService` (transactional). The raw refresh token is made and hashed by Phase 1's **`TokenCodec`**.
7. **`LoginService`** keeps its shape (not transactional, Phase 1's rollback lesson): authenticate → **start a session** → record `LOGIN_SUCCEEDED` → issue the access token.
8. **`CookieSettings`** (web layer, `user`): builds the `Set-Cookie` header to set or clear the refresh cookie, so the attributes live in one place.

**The claims**

| Claim | Value | Why it's there |
|---|---|---|
| header `alg` / `kid` / `typ` | `RS256` / the key id / `JWT` | The decoder only accepts RS256 (no algorithm confusion); `kid` makes rotation possible |
| `iss` | the configured issuer | A token from another issuer is rejected |
| `aud` | the configured audience (`taskflow-api`) | A token minted for some other audience by the same issuer is rejected |
| `sub` | the user **id**, as a string | Stable and not PII; the email can change (backlog), the id can't |
| `sid` | the session id | The per-request liveness check (D5) |
| `preferred_username` | the app username | The auditor writes it to `created_by` without a lookup (the standard OIDC claim name) |
| `role` | `USER` / `ADMIN` | Authorities without a lookup; can be stale for up to the access lifetime |
| `iat` / `exp` | issue time / issue time + 15 min | Expiry, checked by the decoder |

**Not in the token:** the email (PII, readable by anyone holding it), the password hash, the lock and verification flags.

#### The choices

| Choice | Problem it solves | What it avoids |
|---|---|---|
| **Opaque refresh token** (32 random bytes, SHA-256 at rest, Phase 1's `TokenCodec`) | A long-lived credential that the server can always check and kill | A self-contained long-lived bearer credential nobody can revoke; claims leaking from it |
| **Two tables: `user_sessions` (the family) + `refresh_tokens`** | Revocation is **one row** (`revoked_at` on the session), shared by refresh tokens *and* access tokens (via `sid`) | Updating N token rows to revoke; a "list my sessions" built from a `GROUP BY` |
| **One live refresh token per session, enforced by a partial unique index** | A rotation bug or a race can never leave two live tokens in one family | Two tokens both rotating independently, forking the family (your §1.4 pattern, reused) |
| **Sessions and tokens extend `IdentifiedEntity`, with domain-named timestamps** | `created_at`, `last_refreshed_at`, `revoked_at` say what happened | `created_by`/`updated_by` that are always `system` (login and refresh are anonymous requests) |
| **Insert-only entities: mutable columns `updatable = false`, changes only through conditional `@Modifying` updates** *(your approach, D11)* | No entity flush can write a stale `revoked_at`, `consumed_at` or `last_refreshed_at` back | Phase 1's whole-row write undoing a concurrent change, by construction rather than by care |
| **`SessionService.start` is its own transaction; `LoginService` stays non-transactional** | The session commits on its own, before the response | Phase 1's rollback trap: one failure undoing the counter or the event |
| **Session created only after `authenticate()` succeeds**, and before `LOGIN_SUCCEEDED` is recorded | Unverified and locked logins never get a session; a failed session insert can't leave a "succeeded" event for a login that returned 500 | Sessions for users who couldn't log in; history that lies |
| **Cookie: `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age` = the token's idle lifetime** ↺ D4 | JavaScript can't read it; it's only sent to the auth endpoints; it dies with the token | XSS stealing it (D4); sending it with every API call |
| **Access token in the body with `tokenType: "Bearer"` and `expiresIn` in seconds** | The client schedules its refresh from `expiresIn` | Clients decoding the JWT to find `exp` (and depending on its format) |
| **`Cache-Control: no-store` comes from Spring Security's default headers** (*verified*: `no-cache, no-store, max-age=0, must-revalidate` on every response) | Token responses must not be cached (RFC 6749 §5.1) | Writing code for something that's already there. Test it instead. |

#### Alternatives we didn't take

| Alternative | Why not | The concrete problem it would cause later |
|---|---|---|
| A **JWT as the refresh token** | Self-contained means unrevocable without a lookup, and the lookup is the whole point of a refresh token | §2.4's log-out-everywhere would need a deny-list of every refresh token ever issued; a stolen one would work for 14 days |
| Storing the **raw** refresh token | A database leak would hand out every session | Phase 1's rule: any backup or read replica becomes a list of working credentials |
| **One table** (`refresh_tokens` with a `family_id`, no sessions table) | Revoking a family means updating every token row; "is this session live?" becomes "is any token in this family live?" | §2.2's per-request check would query a non-unique column and race with rotation; §2.4's list would aggregate |
| **`BaseEntity`** on sessions | The audit columns would always say `system` | Noise columns, and an auditor call on every refresh write |
| **The email** in the claims | It's PII, readable in every log that captures headers | A support engineer pastes a token into a ticket, and an email leaks; and tokens go stale on email change (backlog) |
| The **ephemeral key in prod** | Every instance generates its own key | Phase 11's compose with two app instances: every other request lands on the instance that didn't sign the token → 401 → a refresh storm |
| A **committed dev key** | Private keys in a repo get flagged, copied, and reused | A dev key quietly ends up in a prod config |
| **Loading the user** on every request instead of carrying `preferred_username` and `role` | Removes staleness | A second lookup per request; D5's session check is enough |

### What we're optimising for

**Revocability** (one switch per session), **no long-lived secret readable by JavaScript**, **no PII in tokens**, and **fail-closed configuration** (prod without a key doesn't start). We trade token size, a little signing cost, and cookie handling for these.

### Concepts

💡 **Asymmetric signing.** The private key signs; the public key only verifies. Whoever holds only the public key can't mint tokens, which is why it can be shared (D2).
💡 **Cookie attributes.** `HttpOnly`: no JavaScript access. `Secure`: HTTPS only (browsers also treat `http://localhost` as secure; see the trap). `SameSite=Strict`: never sent on cross-site requests. `Path`: only sent to URLs under it. `Max-Age`: deleted after it.
💡 **Why the refresh token is hashed with SHA-256, not bcrypt** (Phase 1's lesson, again): 256 bits of randomness can't be guessed at any speed, and the database must find the row **by** the hash.

### Requirements

#### The migration (V5): you apply it; the SQL is below

- [x] `V5__create_user_sessions.sql` is added exactly as given, and the app starts with `ddl-auto: validate`.

```sql
-- One row per login: the "family" every refresh token of that login belongs to.
-- Revoking the session is the single switch that kills its refresh tokens AND its access tokens
-- (the access token carries the session id as the `sid` claim and is checked against this row).
create table user_sessions (
    id                bigint       not null,
    user_account_id   bigint       not null,
    created_at        timestamptz  not null,
    expires_at        timestamptz  not null,
    last_refreshed_at timestamptz  not null,
    revoked_at        timestamptz,
    revoke_reason     varchar(32),
    ip_address        inet,
    user_agent        varchar(512),

    constraint pk_user_sessions primary key (id),
    constraint fk_user_sessions_user_account
        foreign key (user_account_id) references user_accounts (id) on delete cascade,
    -- Both instants come from one Clock reading in one factory method, so comparing them is safe
    -- (unlike Phase 1's created_at, which the auditor sets from a different clock).
    constraint ck_user_sessions_expiry check (expires_at > created_at),
    constraint ck_user_sessions_refreshed_after_created check (last_refreshed_at >= created_at),
    constraint ck_user_sessions_revoke_reason
        check (revoke_reason in ('LOGOUT', 'LOGOUT_ALL', 'REVOKED_BY_USER',
                                 'PASSWORD_CHANGED', 'PASSWORD_RESET', 'REFRESH_TOKEN_REUSE')),
    -- A revoked session always says why; a live one never has a reason.
    constraint ck_user_sessions_reason_iff_revoked
        check ((revoked_at is null) = (revoke_reason is null))
);

-- Serves the FK cascade, "revoke all sessions of user X" and "list my sessions".
create index ix_user_sessions_user_account on user_sessions (user_account_id);

-- The opaque refresh tokens. Only the SHA-256 hash is stored (same codec as user_tokens).
-- No revoked_at here on purpose: revocation lives on the session, in one place.
create table refresh_tokens (
    id          bigint       not null,
    session_id  bigint       not null,
    token_hash  varchar(64)  not null,
    created_at  timestamptz  not null,
    expires_at  timestamptz  not null,
    consumed_at timestamptz,

    constraint pk_refresh_tokens primary key (id),
    constraint uk_refresh_tokens_token_hash unique (token_hash),
    constraint fk_refresh_tokens_session
        foreign key (session_id) references user_sessions (id) on delete cascade,
    constraint ck_refresh_tokens_hash_format check (token_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_refresh_tokens_expiry check (expires_at > created_at),
    constraint ck_refresh_tokens_consumed_after_created check (consumed_at is null or consumed_at >= created_at)
);

-- Serves the FK cascade (must include consumed rows, so it can't be the partial index below).
create index ix_refresh_tokens_session on refresh_tokens (session_id);

-- At most one unconsumed token per session, enforced by the database:
-- a rotation bug or a race can never leave two live tokens in one family.
create unique index uk_refresh_tokens_active on refresh_tokens (session_id) where consumed_at is null;
```

**`user_sessions`, column by column**

| Column | Type | Used for | Constraint / index, and why |
|---|---|---|---|
| `id` | `bigint` | Primary key, from `global_id_seq` like every table; **also the `sid` claim** | `pk_user_sessions`: the per-request check is a lookup by it |
| `user_account_id` | `bigint`, not null | The owner. The per-request check also confirms `sid` belongs to `sub` | FK, `ON DELETE CASCADE` (erasing a user erases their sessions, Phase 1 decision 19's stance). `ix_user_sessions_user_account` serves the cascade, "revoke all of user X" and the list |
| `created_at` | `timestamptz`, not null | Login time; start of the absolute lifetime | — |
| `expires_at` | `timestamptz`, not null | Absolute end (login + 30 days ↺ D3). Refresh and every request are refused after it | `ck_user_sessions_expiry`: safe here because both instants come from **one** `Clock` reading (contrast Phase 1's removed check, which compared two clocks) |
| `last_refreshed_at` | `timestamptz`, not null | "Last active" in the session list. Its granularity is the access lifetime: there's deliberately **no write per request** | `ck_user_sessions_refreshed_after_created` |
| `revoked_at` | `timestamptz`, null | **The** revocation switch for the session, its refresh tokens and its access tokens | `ck_user_sessions_reason_iff_revoked` |
| `revoke_reason` | `varchar(32)`, null | Why it ended: forensics, and tests can assert the right path did it | `ck_user_sessions_revoke_reason` lists **all six** reasons now, so §2.3–§2.4 need no migration |
| `ip_address` | `inet`, null | The device list | `inet` validates and canonicalises (Phase 1); null when the socket address doesn't parse |
| `user_agent` | `varchar(512)`, null | The device list | Same length as `security_events`; `ClientInfo` already truncates to it |

**`refresh_tokens`, column by column**

| Column | Type | Used for | Constraint / index, and why |
|---|---|---|---|
| `id` | `bigint` | Primary key | `pk_refresh_tokens` |
| `session_id` | `bigint`, not null | The family | FK, `ON DELETE CASCADE`; `ix_refresh_tokens_session` serves the cascade (it must include consumed rows) |
| `token_hash` | `varchar(64)`, not null | SHA-256 hex of the raw token; how a presented token is found | `uk_refresh_tokens_token_hash` (lookup, and a collision guard); `ck_refresh_tokens_hash_format`: 64 lowercase hex, so a raw token can never be stored by mistake |
| `created_at` | `timestamptz`, not null | Issue time | — |
| `expires_at` | `timestamptz`, not null | Idle expiry: the earlier of issue + 14 days and the session's `expires_at` ↺ D3 | `ck_refresh_tokens_expiry` (one clock, as above) |
| `consumed_at` | `timestamptz`, null | Set when the token is rotated. Reuse detection and the grace window read it (§2.3) | `ck_refresh_tokens_consumed_after_created`; **`uk_refresh_tokens_active`**: at most one unconsumed token per session |

**Deliberately absent:** `revoked_at` on tokens (revocation is the session's job), `user_account_id` on tokens (reached through the session; one less thing to keep consistent).

**Verified 2026-10-01** on the dev DB, inside a transaction that was **rolled back** (explicit ids from 990000001, no `nextval`: `global_id_seq` stayed at 1801; afterwards the tables don't exist and V4's constraint is unchanged):
- a second live token in one session → `uk_refresh_tokens_active`; after consuming the first, the successor inserts fine;
- the conditional consume returns **1 row, then 0** for the same token (the race loser's view);
- each `CHECK` rejects its bad row (hash format, both expiries, consumed-before-created, refreshed-before-created, reason without revoke, revoke without reason, an unknown reason); a session for a missing user → the FK;
- revoke-all updates only the live session (1 row);
- deleting the user cascades to sessions and tokens (and events, already the case);
- plans (with `enable_seqscan` off, because the tables were empty): the token lookup uses `uk_refresh_tokens_token_hash`; revoke-all uses `ix_user_sessions_user_account`; the per-request lookup also chose `ix_user_sessions_user_account` on the empty table. 📊 Re-check that plan with real rows in §2.2: expect the primary key.

#### `pom.xml`

- [x] `spring-boot-starter-oauth2-resource-server` is added, with no version (Boot manages it). ↺ D1

#### `JwtProperties` (`taskflow.security.jwt`, `common/security`)

- [x] `issuer` and `audience` are required, non-blank.
- [x] `access-token-ttl` is required and positive (15 min ↺ D3).
- [x] `clock-skew` is required and not negative (5 s; see §2.2).
- [x] `key-id`, `private-key` and `public-key` are optional as a group: all three or none.
- [x] The keys are supplied as PEM text from environment variables or a file location; neither is ever in `application*.yml` with a real value.

**Done when:** the app refuses to start with a blank issuer or a negative lifetime, naming the property.

#### `JwtKeyConfig` (`common/security`) ↺ D2

- [x] With configured keys, it loads them and checks that the public key matches the private key.
- [x] Without configured keys in `dev` or `test`, it generates a 2048-bit RSA pair and a random key id, and logs one `WARN` (no key material in the log).
- [x] Without configured keys in `prod`, startup fails with a message naming the missing properties.
- [x] It exposes the `JwtEncoder` bean and, for §2.2, the public key.

**Done when:** `dev` starts with the `WARN` line; `prod` with no key refuses to start.

#### `AccessTokenIssuer` (`common/security`)

- [x] It signs with RS256 and puts the key id in the header.
- [x] It sets exactly the claims in the table above, and no others.
- [x] `iat` is the "now" it's given (from the injected `Clock`), and `exp` is `iat` + the access lifetime.
- [x] It returns the token and its expiry.

**Done when:** a token decoded by hand (`base64 -d` on the middle part) shows those claims and no email.

#### `SessionProperties` (`taskflow.security.sessions`, `user.session`)

- [x] `refresh-token-idle-ttl` (14 d), `absolute-ttl` (30 d) and `reuse-grace` (10 s) are required; the grace may be zero. ↺ D3, D6
- [x] The cookie's `name`, `path` (`/api/v1/auth`), `secure` (default `true`) and `same-site` (`Strict`) are configurable. ↺ D4

#### `UserSession` and `RefreshToken` (entities, `user.session`)

- [x] Both extend `IdentifiedEntity`; neither has setters.
- [x] `UserSession` is created by one factory from the account id, the client info, "now" and the absolute lifetime (one clock reading for `created_at`, `last_refreshed_at` and `expires_at`).
- [x] `RefreshToken` is created by one factory from the session, the hash, "now" and the idle expiry (already capped at the session's end).
- [x] `RefreshToken` → `UserSession` is `LAZY` (Phase 1 decision 16's reasoning).
- [x] Neither `toString()` prints a hash, an IP or a user agent.

#### `SessionService.start` (`user.session`)

- [x] It's `@Transactional` and creates one session and its first refresh token together.
- [x] It returns the session id and the **raw** refresh token in a result type whose `toString()` masks the token.
- [x] The refresh token's expiry is the earlier of now + idle lifetime and the session's expiry.

**Done when:** after a login, `user_sessions` has one live row and `refresh_tokens` has one unconsumed row whose hash is **not** the cookie's value.

#### `LoginService` and `AuthController.login`

- [x] The authentication and its failure handling are unchanged (same 401/403 rules as Phase 1).
- [x] After a successful `authenticate()`, it starts a session, **then** records `LOGIN_SUCCEEDED`, then issues the access token.
- [x] No session is created when authentication fails, for any reason.
- [x] The controller sets the refresh cookie through `CookieSettings` and returns the body below.
- [x] Neither the raw refresh token nor the access token is logged anywhere.

**Endpoint**

| Method | Path | Request body | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/auth/login` | `{email, password}` (unchanged) | **200** `{accessToken, tokenType: "Bearer", expiresIn, user: {id, email, username, displayName, emailVerified, createdAt}}` + `Set-Cookie: <name>=<raw refresh token>; Path=/api/v1/auth; Max-Age=…; HttpOnly; Secure; SameSite=Strict` | 400 `VALIDATION_FAILED` · 401 `AUTHENTICATION_FAILED` · 403 `EMAIL_NOT_VERIFIED` (all unchanged) |

**Done when:** a login with `curl -c jar` returns the body, the jar holds the cookie, and the response carries `Cache-Control: no-store`.

### Traps ⚠️

- **The payload is readable.** Base64url isn't encryption. A claim you "only use internally" is visible to anyone with the token.
- **Records print every component** *(Phase 1, hit)*. The login result and the session-start result hold a raw refresh token; mask them, or one `log.debug("{}", result)` puts a 14-day credential in the log.
- **Order: session before `LOGIN_SUCCEEDED`.** The other way round, a failed session insert leaves history saying "succeeded" for a login that returned 500.
- **Don't make `login` `@Transactional`** to "keep it consistent": that's Phase 1's rollback trap (the failure counter and events must survive a failed authentication).
- **`expiresIn` is seconds.** `Duration.toMillis()` here makes clients wait 1000× too long to refresh.
- **`Secure` cookies over `http://localhost`.** *Verified 2026-10-07 with curl 8.7.1 (macOS) against a throwaway server:* the jar keeps the cookie for `localhost` and `127.0.0.1` with its `Secure` flag, sends it back to `/api/v1/auth/*` only, and never to `/api/v1/organizations`. So `dev` keeps `secure: true`. ⚠️ In the jar file the line starts with `#HttpOnly_localhost`, which looks like a comment: `grep -v '^#'` hides it (it fooled my first check).
- **The ephemeral key and restarts.** After a dev restart, every access token fails (§2.2). That's expected; the client refreshes. It's also why the ephemeral key is never allowed in `prod`.
- **`common` never imports a feature** *(Phase 1, hit twice)*. `AccessTokenIssuer` takes ids and strings, not a `UserAccount`.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| 1. Add an `email` claim, log in, decode the middle part with `base64 -d` | The email in plain text | Signed ≠ encrypted. Claims are public. |
| 2. Remove the mask from the session-start result and log it at INFO | A raw refresh token in the console | Every record holding a secret masks it |
| 3. In `psql`, insert a second unconsumed token for one session | `uk_refresh_tokens_active` refuses it | The database enforces "one live token per family", whatever the code does |
| 4. Start with `--spring.profiles.active=prod` and no key | Startup fails, naming the key properties | Fail closed on missing secrets, at startup, not on the first login |

### Build order

1. Add the starter; start the app; confirm nothing changed (no `JwtDecoder` is auto-configured without properties, *verified* in Boot's `OAuth2ResourceServerJwtConfiguration`).
2. V5: apply, start with `validate`. → *Deliberate failure 3.*
3. `JwtProperties`, `JwtKeyConfig`, the encoder. Start in `dev` (the `WARN`), then `prod` without a key. → *Deliberate failure 4.*
4. `AccessTokenIssuer` and its unit test.
5. The entities, repositories, `SessionProperties`, `SessionService.start`.
6. Wire `LoginService` and the controller; `CookieSettings`.
7. Run it: log in with a cookie jar, decode the token by hand, look at both tables. → *Deliberate failures 1 and 2.*

### Tests ⏭️ skipped 2026-10-07 (D10); the list stays for when they're written

| Kind | Must prove |
|---|---|
| Unit: `AccessTokenIssuer` | Decoding the token with a decoder built from the public key gives exactly the claims in the table, `exp` = `iat` + lifetime to the second (with `Clock.fixed`), header `kid` and `RS256`; **no `email` claim** |
| Unit: refresh expiry | Capped at the session's end when the session ends sooner than the idle lifetime |
| Unit: result types | `toString()` never contains the raw refresh token |
| JPA slice | V5: the partial unique index (**flush**, testing guide §6.3), a raw (non-hex) hash is refused, the cascade from `user_accounts` |
| Integration | Login → 200 with the body shape; `Set-Cookie` has `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1/auth`, a `Max-Age`; `Cache-Control` contains `no-store`; one session and one token row; the stored hash ≠ the cookie value · unverified login (right password) → 403 and **no** session row · wrong password → 401 and **no** session row |
| Config | `prod` without a key fails to start (💡 new tool: Boot's `ApplicationContextRunner` starts a tiny context with just your config classes and properties, in milliseconds) |

---

## 2.2 — Bearer authentication on every request

### What we're building

Every `/api/v1/**` request now authenticates with `Authorization: Bearer <access token>`. A token whose session was revoked is refused **on the next request**. HTTP Basic is deleted. 401 and 403 from the filter chain come back as the same `ProblemDetail` as every other error. The principal becomes a small `AuthenticatedUser`, and everything that read `TaskflowPrincipal` follows.

- **In:** the resource-server configuration, the decoder and its validators, the session check, the converter, a bearer resolver that ignores `/api/v1/auth/**`, the entry point, the access-denied handler, removing Basic, migrating the tests.
- **Out:** refresh (§2.3), logout (§2.4), CORS (§2.5).

### How we're building it, and why

**The moving parts**

1. **`JwtDecoder`** bean: built from the public key, RS256 only. Its validator is the default timestamp check **with your `Clock` and skew**, plus issuer and audience. ↺ D1
2. **`SessionJwtConverter`** (`common/security`): turns a validated `Jwt` into a **`TaskflowAuthenticationToken`** whose principal is **`AuthenticatedUser(id, username, role, sessionId)`** and whose authority is `ROLE_<role>`. Before that, it asks **`SessionStatus`** whether the session is live; if not, it throws `InvalidBearerTokenException` (a 401). ↺ D5
3. **`SessionStatus`** (an interface in `common/security`), implemented in `user.session`: "is session *sid* of user *sub* live at *now*?" One indexed query.
4. **`BearerTokenResolver`** bean: Spring's default resolver, except it returns "no token" for `/api/v1/auth/**`.
5. **`ProblemAuthenticationEntryPoint`** and **`ProblemAccessDeniedHandler`** (`common/security`): write `application/problem+json` with `code`, `correlationId` and `instance`, like `GlobalExceptionHandler`. The entry point also sets `WWW-Authenticate: Bearer` (with `error="invalid_token"` when a token was presented).
6. **The chain**: `httpBasic` removed; `oauth2ResourceServer` added with the decoder, converter, resolver and entry point; `exceptionHandling` gets the same entry point and the access-denied handler; the rules are unchanged except two new `permitAll` paths for §2.3–§2.4.
7. **Everything that read `TaskflowPrincipal`**: `AuditAwareImpl`, `UserController`'s `@AuthenticationPrincipal`, and `PasswordWorkflow.changePassword` (which used the principal's email to re-authenticate, and must now load it by id).

**Where each check happens** (*verified* in 6.5.11's sources): the filter resolves the header → the provider calls `decoder.decode()`: signature first (an unsigned or forged token never reaches the validators), then **all** validators (they don't short-circuit) → only then the converter. Putting the session check in the **converter** means the database is only consulted for tokens that are genuine, unexpired and ours.

**Error codes** (in `CommonErrorCode`, because the gate is in `common`)

| Situation | Status | Code | `WWW-Authenticate` |
|---|---|---|---|
| No token, protected path | 401 | `AUTHENTICATION_REQUIRED` | `Bearer` |
| Token presented and rejected (malformed `Bearer` header, bad signature, expired, wrong issuer/audience, revoked or expired session) | 401 | `INVALID_ACCESS_TOKEN` | `Bearer error="invalid_token"` |
| Several bearer tokens in one request | 400 (Spring's `BearerTokenError` status, kept) | `INVALID_ACCESS_TOKEN` | `Bearer error="invalid_request"` |

*(Verified in `DefaultBearerTokenResolver` and `BearerTokenErrors`: a header starting with `Bearer` that doesn't match the pattern is `invalid_token`/401; several tokens are `invalid_request`/400.)*
| Authenticated but not allowed (`denyAll`, `hasRole`) | 403 | `ACCESS_DENIED` | — |

The `detail` is a fixed sentence per code. **Never the exception's message**: the JWT library's messages describe the token ("Jwt expired at …").

#### The choices

| Choice | Problem it solves | What it avoids |
|---|---|---|
| **Your own `JwtDecoder` bean**, not Boot's properties | The validators, clock and skew are yours | Boot's decoder (only created from `spring.security.oauth2.resourceserver.jwt.*` properties, *verified*) with its system clock |
| **`JwtValidators.createDefaultWithValidators(...)`**, with your own `JwtTimestampValidator` in the list | Issuer and audience are **added to** the expiry check | Replacing it: `setJwtValidator(...)` **replaces** the default, which is the only thing checking `exp` (*verified*; trap below) |
| **The timestamp validator gets the injected `Clock`** (`setClock`) and an explicit skew | Tests can move time; production uses the system clock anyway | Its default `Clock.systemUTC()` (*verified*), which ignores your test clock |
| **Skew 5 s**, not the default 60 s | One app issues and verifies, so the only skew is between instances | A token working 60 s past its `exp`; tests at `exp + 1s` that unexpectedly pass |
| **Session check in the converter** | Only genuine, unexpired tokens cost a query; a database error propagates as a 500 (not a 401) | Querying for every expired token (validators don't short-circuit, *verified*); an outage reported as "invalid token" |
| **A custom `Authentication` with an `AuthenticatedUser` principal** | Controllers and the auditor read one small type with id, username, role, session id | Reading claims by string name all over the app; Spring's `JwtAuthenticationToken`, whose principal is the raw `Jwt` |
| **`TaskflowPrincipal` stays, only for password authentication** | `DaoAuthenticationProvider` still needs a `UserDetails` at login and change-password | A request principal pretending to have a password hash and status flags |
| **An unknown `role` value is a rejected token**, not a default | A token can't grant a role the enum doesn't know | Defaulting to `USER` (or worse) on a typo or an old token format |
| **The resolver ignores `/api/v1/auth/**`** | Login, refresh and logout work even when the client still attaches an expired token | §2.3's refresh returning 401 for exactly the clients that need it (*verified*: an invalid token is rejected **before** authorization, even on `permitAll`) |
| **The entry point set in both places** | Missing-token and invalid-token 401s look the same | Half your 401s keeping Spring's empty body: the bearer filter holds **its own** entry point (*verified*: set on the filter by `OAuth2ResourceServerConfigurer`) |

#### Alternatives we didn't take

| Alternative | Why not | The concrete problem it would cause later |
|---|---|---|
| Session check as an `OAuth2TokenValidator` | Works, but validators all run, so expired tokens also hit the database | Under a client bug that retries an expired token in a loop, every retry is a query |
| Session check in a separate filter after the bearer filter | You'd rebuild the 401 handling the resource server already has | Two places that decide "401", which drift (Phase 1's brace-pattern lesson: rules fail per request) |
| Reusing **`TaskflowPrincipal`** as the bearer principal | It would carry a null password and meaningless flags | Worse: `AuthenticationEventsListener.onSuccess` resets the failure counter for a `TaskflowPrincipal`, and a success event is published on **every** bearer request (*verified*: `HttpSecurity`'s manager has Boot's publisher). A write per request, **and** the user's own open tab resets the counter while an attacker guesses the password: lockout never triggers |
| `role` read from the database each request | No staleness | A second query per request for a fact that changes by hand, rarely (see the trap on manual role changes) |
| Keeping Basic alongside bearer | "Convenient for curl" | A password sent on every request, a second lockout path, and every rule tested twice |

### What we're optimising for

**Immediate revocation**, **one error model** for every 401/403, **fail closed** (an outage is a 500, never a pass or a "wrong token"), and **least surprise for clients** (RFC 6750 headers, auth endpoints never broken by a stale header).

### Concepts

💡 **Authentication happens before authorization, for every request.** A `permitAll()` rule doesn't stop the bearer filter rejecting a bad token first. "Public" means "no token needed", not "tokens ignored".
💡 **The auditor follows the principal type.** It checks `instanceof`; the day the principal type changes, it silently writes `system`. The test is the only alarm.
🔍 **Look inside:** `BearerTokenAuthenticationFilter.doFilterInternal` (resolve → authenticate → set context, or entry point), `JwtAuthenticationProvider.authenticate` (decode → convert; `BadJwtException` → 401, other `JwtException` → `AuthenticationServiceException`), `NimbusJwtDecoder.decode` (unsigned → rejected, then validate). Sources are in `~/.m2` now.
🎯 **Interview question:** *"A user clicks 'log out everywhere'. What happens to the access token in the attacker's hands?"* Write the answer once this works.

### Requirements

#### `JwtDecoder` bean (`common/security`) ↺ D1, D2

- [ ] It verifies with the public key from `JwtKeyConfig` and accepts only RS256.
- [ ] Its validators are: timestamps (injected `Clock`, configured skew), issuer, audience.
- [ ] They're combined with `JwtValidators.createDefaultWithValidators`, never set alone.

**Done when:** an expired token, a token with another `aud`, and a token signed by another key each get 401 `INVALID_ACCESS_TOKEN`.

#### `SessionStatus` (interface, `common/security`) and its implementation (`user.session`) ↺ D5

- [ ] It answers whether a session id belongs to a user id, isn't revoked, and hasn't passed its `expires_at`.
- [ ] The query selects no entity (a projection or `exists`): it's on the path of every request.
- [ ] A database error propagates.

#### `SessionJwtConverter` (`common/security`)

- [ ] It rejects a token missing `sid`, `sub`, `preferred_username` or `role`, or with an unknown role.
- [ ] It rejects a token whose session isn't live (`SessionStatus`).
- [ ] It builds `TaskflowAuthenticationToken` with an `AuthenticatedUser` principal and one `ROLE_` authority, marked authenticated.
- [ ] `getName()` returns the username.

#### `AuthenticatedUser` and `TaskflowAuthenticationToken` (`common/security`)

- [ ] `AuthenticatedUser` holds id, username, role and session id, and nothing else.
- [ ] The token's credentials are not exposed (`getCredentials()` returns nothing useful).

#### `BearerTokenResolver` bean (`common/security`)

- [ ] It returns no token for any path under `/api/v1/auth/`.
- [ ] Everywhere else it behaves like Spring's default (header only; no query parameter; several tokens → 400).

#### `ProblemAuthenticationEntryPoint` and `ProblemAccessDeniedHandler` (`common/security`)

- [ ] They write `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, `code`, `timestamp` and `correlationId`, matching `GlobalExceptionHandler`.
- [ ] They build the body through one shared factory in `common/error`, not a copy of the handler's code.
- [ ] The entry point sets `WWW-Authenticate` as in the error-code table and keeps the `BearerTokenError` status.
- [ ] The `detail` never contains an exception message.

**Done when:** an anonymous `GET /api/v1/organizations`, an expired token, and a `USER` calling `/actuator/metrics` each return a `ProblemDetail` with a `correlationId`.

#### `TaskflowSecurityConfig`

- [ ] `httpBasic` is removed.
- [ ] `oauth2ResourceServer` uses the decoder, the converter, the resolver and the entry point.
- [ ] `exceptionHandling` uses the same entry point and the access-denied handler.
- [ ] `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout` are added to the `permitAll` list.
- [ ] Every other rule is unchanged, and `denyAll()` is still last.
- [ ] The CSRF comment is rewritten: bearer headers aren't attached by browsers; the two cookie endpoints are protected in §2.3.

#### `AuditAwareImpl`, `UserController`, `PasswordWorkflow`

- [ ] The auditor writes `AuthenticatedUser.username`, and `system` otherwise.
- [ ] `/users/me/*` endpoints take `@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser`.
- [ ] `changePassword` loads the account's email by the principal's id before re-authenticating through the manager.
- [ ] `AuthenticationEventsListener.onSuccess` still reacts **only** to `TaskflowPrincipal` (password authentications).

**Done when:** an organization created with a bearer token has `created_by` = the username; changing a password with a bearer token works and still counts a wrong current password.

#### Tests that used Basic

- [ ] `TaskflowSecurityIntegrationTest` and `OrganizationApiIntegrationTest` log in through `/auth/login` (a small helper next to `TestUsers`) and send the bearer token.
- [ ] The security slice provides a mocked `JwtDecoder` and `SessionStatus` (the config needs them to start).

**Access table after §2.2** (replaces the Basic rows; the rest of Phase 1's table stands)

| Caller | Request | Expected |
|---|---|---|
| anonymous | `GET /api/v1/organizations` | 401 `AUTHENTICATION_REQUIRED` |
| valid token | `GET /api/v1/organizations` | 200 |
| expired / wrong-key / wrong-`aud` token | `GET /api/v1/organizations` | 401 `INVALID_ACCESS_TOKEN` |
| token whose session is revoked (set `revoked_at` by SQL) | `GET /api/v1/organizations` | 401 `INVALID_ACCESS_TOKEN` |
| `Authorization: Basic …` | `GET /api/v1/organizations` | 401 (Basic is gone) |
| expired token attached | `POST /api/v1/auth/login` | 200 / 401 by the password only (the token is ignored) |
| `USER` token | `GET /actuator/metrics` | 403 `ACCESS_DENIED` |
| any token | `GET /nope` | 403 `ACCESS_DENIED` (`denyAll`) |

### Traps ⚠️

- **`setJwtValidator(audienceValidator)` turns off expiry.** The default validator is the only thing checking `exp`; setting your own **replaces** it (*verified* in `NimbusJwtDecoder`). Use `createDefaultWithValidators`.
- **The timestamp validator ignores your `Clock`** unless you call `setClock` (*verified*: it defaults to `Clock.systemUTC()`). Tests with `Clock.fixed` issue tokens whose `exp` is "in the past" to the decoder.
- **An invalid token on a `permitAll` path is still a 401** (*verified*: the filter calls the entry point when authentication fails, before authorization runs). Clients attach the token everywhere; hence the resolver rule.
- **Half the 401s with an empty body.** `exceptionHandling().authenticationEntryPoint(...)` covers "no token"; the bearer filter uses the resource server's own entry point for "bad token" (*verified*). Set both.
- **`@AuthenticationPrincipal(errorOnInvalidType = true) TaskflowPrincipal`** throws `ClassCastException` once the principal changes: every `/users/me` endpoint becomes a 500.
- **The auditor silently writes `system`** once the principal type changes. Nothing fails; only the `created_by` assertion catches it.
- **Change-password used the principal's email** (`getUsername()`). The bearer principal has no email; don't add one to the token to "fix" it.
- **A success event on every request** (*verified*). Harmless only while the listener ignores non-`TaskflowPrincipal` authentications. Don't "simplify" that check away.
- **`common` never imports a feature** *(hit twice)*: `SessionStatus` is the interface in `common`, implemented in `user.session`, exactly like `ErrorCode`.
- **Manual role changes are stale for up to 15 minutes.** After `update user_accounts set role = 'ADMIN'` by hand, revoke that user's sessions too (or wait).
- **A database outage must be a 500, not a 401.** Don't catch `DataAccessException` in `SessionStatus` or the converter (Phase 1's lockout listener lesson: a swallowing `catch` hid a broken query).
- **Spring's `jwt()` test post-processor builds `JwtAuthenticationToken`** (*verified* in spring-security-test 6.5.11), not your token. In tests that reach `@AuthenticationPrincipal AuthenticatedUser` or the auditor, use `authentication(new TaskflowAuthenticationToken(...))`.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| 1. Replace the validator with only the audience validator; call with an expired token | **200** | Custom validators replace the defaults; expiry is a validator |
| 2. Remove the resolver's `/api/v1/auth/**` rule; log in with an expired token in the header | 401 from **login** | Authentication runs before `permitAll` |
| 3. Set the entry point only in `exceptionHandling`; send an expired token | A 401 with an **empty body** and a `WWW-Authenticate` header | The bearer filter has its own entry point |
| 4. Point `@AuthenticationPrincipal` back at `TaskflowPrincipal` | 500 on `/users/me/login-history` | `errorOnInvalidType` fails loudly; good |
| 5. Leave the auditor on `TaskflowPrincipal`; create an organization | `created_by = system` | The silent one: only a test catches it |
| 6. Craft a token with `{"alg":"none"}` (header and payload base64url, empty signature) | 401 | The decoder rejects unsigned tokens (*verified* in source; see it in practice) |
| 7. Restart the app (ephemeral dev key), reuse the old access token | 401 `INVALID_ACCESS_TOKEN` | Keys are per process in dev; §2.3's refresh is the way back |
| 8. Revoke the session by SQL; call with the still-unexpired token | 401 immediately | D5 working: the token is fine, the session isn't |

### Build order

1. The decoder and its validators; temporarily add `oauth2ResourceServer` next to Basic. Call with a token from §2.1. → *Deliberate failures 1, 6.*
2. `AuthenticatedUser`, the token class, `SessionStatus` + implementation, the converter. → *Deliberate failure 8.*
3. The entry point, access-denied handler, shared `ProblemDetail` factory; wire both places. → *Deliberate failure 3.*
4. The resolver rule. → *Deliberate failure 2.*
5. Remove Basic. Fix the auditor, `UserController`, `changePassword`. → *Deliberate failures 4, 5.*
6. Migrate the Basic tests; run the suite. 📊 Container count and time vs Phase 1's 76 tests / 2 containers.
7. → *Deliberate failure 7.*

### Tests

| Kind | Must prove |
|---|---|
| Unit: converter | Missing `sid` / `sub` / `preferred_username` / `role` → rejected · unknown role → rejected · dead session → rejected · live session → `AuthenticatedUser` with the right fields and exactly one `ROLE_` authority |
| Unit: decoder | With the adjustable clock: valid at `exp − 1s`, rejected at `exp + skew + 1s` · wrong `iss` / `aud` / key → rejected · `alg: none` → rejected |
| Unit: entry point | Missing token → 401 `AUTHENTICATION_REQUIRED` + `WWW-Authenticate: Bearer` · invalid token → `error="invalid_token"` · the body never contains the exception message |
| Unit: auditor | `AuthenticatedUser` → username · anonymous → `system` · a foreign principal type → `system` |
| Web slice (security) | The table above as a parameterised test (`authentication(...)` for "valid token", a mocked decoder throwing for "invalid") · `/api/v1/auth/*` with an invalid token header → let through |
| Integration | Login → bearer → 200 · revoke the session by SQL → the same token → 401 · an organization's `created_by` = username · change-password with a bearer token → 204, a wrong current password → 400 and the counter moves · no `Set-Cookie` on a bearer request · 401 bodies carry `correlationId` |

---

## 2.3 — Refresh: rotation and reuse detection

### What we're building

`POST /api/v1/auth/refresh`, called with the refresh cookie, returns a new access token and **rotates** the cookie: the presented refresh token is consumed and a new one replaces it. Presenting an already-consumed token is treated as theft: the session is revoked and the event recorded (unless it was consumed within the last 10 seconds, the legitimate multi-tab race).

- **In:** `SessionService.refresh`, the refresh endpoint, the `Origin` check for the cookie endpoints, V6 (new event types), the `REFRESH_TOKEN_REUSED` event.
- **Out:** logout and the other revocations (§2.4).

### How we're building it, and why

**The refresh, step by step** (one transaction in `SessionService.refresh`, which **returns a result** instead of throwing):

1. A malformed cookie value → *rejected* (no query; `TokenCodec.isWellFormed`).
2. Find the token by hash. Not found → *rejected*.
3. **Conditional consume:** `UPDATE … SET consumed_at = now WHERE id = ? AND consumed_at IS NULL AND expires_at > now`. **1 row** → continue.
4. **0 rows** → look at the token: expired → *rejected*; consumed **within the grace window** → *superseded* (rejected, **nothing revoked, cookie not cleared**); consumed **before** that → **reuse**: revoke the session (`REFRESH_TOKEN_REUSE`) if it's live, record `REFRESH_TOKEN_REUSED`, *rejected*.
5. **Touch the session conditionally:** `UPDATE user_sessions SET last_refreshed_at = now WHERE id = ? AND revoked_at IS NULL AND expires_at > now`. 0 rows → the session is dead → *rejected*. (Nothing is thrown, so the consume from step 3 commits too: harmless, the token belonged to a dead session.)
6. Insert the successor token (expiry capped at the session's end) and issue an access token with the same `sid` and the user's **current** username and role.

The workflow (outside the transaction) turns *rejected* into a 401 and *refreshed* into a 200 with a new cookie.

**Why return a result instead of throwing inside the transaction:** step 4's revocation and event must **commit even though the request fails**. Throwing a runtime exception inside `@Transactional` rolls them back: reuse detection would detect, then forget. That's Phase 1's rollback trap in a new place.

**The `Origin` check** (refresh and logout only, the two endpoints that read the cookie): if the request has an `Origin` header and it isn't in the allowed origins (§2.5's list), the answer is **403 `ORIGIN_NOT_ALLOWED`** and nothing happens. ↺ D4

#### The choices

| Choice | Problem it solves | What it avoids |
|---|---|---|
| **Conditional `UPDATE`, branch on the row count** | Exactly one of two concurrent refreshes with the same token wins | Check-then-act: both read "unconsumed", both rotate, the family forks (the partial unique index would then throw a 500 on the second insert) |
| **Grace window as a configured duration** ↺ D6 | The multi-tab wake-up race doesn't log the user out | Strict detection punishing a normal browser |
| **Superseded never clears the cookie** | The browser already holds the **winner's** new cookie | A loser's `Set-Cookie: …; Max-Age=0` deleting the new token: logged out after "succeeding" |
| **Reuse revokes the session, not the user** ↺ D6 | Kills exactly the leaked family | Logging out every device on a client bug |
| **Result type out of the transaction; exceptions outside** | The revocation and the event commit with the 401 | Detection rolled back by its own exception |
| **Session touched with a conditional `UPDATE`, not an entity save** | A concurrent revoke can't be undone by the refresh | Hibernate writing the whole row (Phase 1, *verified*): `revoked_at` set back to `NULL`, a revoked session resurrected |
| **Username and role re-read from the account on each refresh** | Role changes reach tokens within one access lifetime | Tokens carrying the role from login for 30 days |
| **Refresh allowed while the account is locked** ↺ D7 | A lock stops password guessing; existing sessions are the owner's | Five bad guesses by anyone logging the owner out of every device |
| **Consumed tokens kept until the session ends** | An old stolen token is still *recognised* as reuse | Deleting on rotation: an old token would look "unknown", and theft would go undetected |
| **One error code for every refresh failure** (`INVALID_REFRESH_TOKEN`) | No oracle about why | Telling a thief "that one was rotated 3 s ago, try again" |
| **`Origin` check on the two cookie endpoints** | Defence in depth behind `SameSite=Strict` | A sibling subdomain (same-*site*, so the cookie is sent) driving logout or refresh |

#### Alternatives we didn't take

| Alternative | Why not | The concrete problem it would cause later |
|---|---|---|
| `SELECT … FOR UPDATE` on the token, then decide in Java | Correct, but two round trips and a lock held across Java code, for what one conditional `UPDATE` does | Under the wake-up burst, refreshes queue on row locks while the Java code runs |
| Delete the consumed token on rotation | Keeps the table small | Reuse becomes indistinguishable from an unknown token: theft undetected (D6-D's failure) |
| Grace response = "here's the token the winner got" | Would make the loser succeed | Impossible without storing raw tokens; and it would hand the thief a live token |
| CSRF tokens (Spring's `CsrfFilter`) for the cookie endpoints | The standard defence for cookie-authenticated forms | A second token for the SPA to fetch and echo, for two endpoints that `SameSite=Strict` + `Origin` already protect |
| Refresh token in the body (D4-B) | — | See D4 |

### What we're optimising for

**Correct under concurrency** (one winner per token, enforced by row counts and an index), **detection that survives its own failure response**, and **no false alarms** from ordinary browsers.

### Concepts

💡 **Why the attacker can't win the race quietly.** Whoever rotates second, thief or owner, presents a consumed token. Outside the grace window, the family dies for both. The thief gets at most the remaining life of one access token, and the owner gets a reason to log in again (and an event in their history).
💡 **SameSite vs same-origin.** `app.example.com` and `evil.example.com` are different **origins** but the same **site**. `SameSite=Strict` stops other sites, not sibling subdomains; the `Origin` check covers that.

### Requirements

#### The migration (V6): you apply it

- [ ] `V6__add_session_security_events.sql` is added exactly as given.

```sql
-- V4 is applied and frozen: the allowed event types change by replacing the constraint, forward only.
alter table security_events drop constraint ck_security_events_event_type;

alter table security_events add constraint ck_security_events_event_type
    check (event_type in ('LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'ACCOUNT_LOCKED',
                          'EMAIL_VERIFIED', 'PASSWORD_RESET', 'PASSWORD_CHANGED',
                          'SESSION_REVOKED', 'ALL_SESSIONS_REVOKED', 'REFRESH_TOKEN_REUSED'));
```

| Change | Why |
|---|---|
| `ck_security_events_event_type` gains `SESSION_REVOKED` (§2.4, a session revoked from the list), `ALL_SESSIONS_REVOKED` (§2.4, log out everywhere), `REFRESH_TOKEN_REUSED` (§2.3) | These are security-relevant actions or attack signals, worth showing in a user's history. A plain logout of this device and a routine refresh are **not** recorded: the session row already says it, and history would drown in them. |
| Same name, replaced, not edited in V4 | Applied migrations are frozen (Phase 1); Flyway would refuse a changed checksum |
| `ck_security_events_reason_iff_login_failed` untouched | Still holds: none of the new types carry a failure reason (*verified*: a reason on `SESSION_REVOKED` is rejected) |

*Verified with V5 in the same rolled-back transaction: a new type inserts; an unknown type is refused; the append-only trigger still rejects `UPDATE`.*

#### `SecurityEventType`, `SecurityEvent`, `SecurityEventRecorder`

- [ ] The three new types exist, each with its own factory method that takes no failure reason.
- [ ] The recorder has one method per new type; each joins the caller's transaction.

#### `SessionService.refresh` (`user.session`)

- [ ] It's `@Transactional` and returns a result: *refreshed* (new raw refresh token, session id, account id) or *rejected* (with whether to clear the cookie).
- [ ] It follows steps 1–6 above, in that order.
- [ ] It never throws for an expected rejection.
- [ ] Reuse revokes the session with reason `REFRESH_TOKEN_REUSE` and records `REFRESH_TOKEN_REUSED` with the presenter's client info, both in this transaction.
- [ ] *Superseded* asks the caller **not** to clear the cookie; every other rejection asks it to clear the cookie.
- [ ] The successor's expiry is the earlier of now + idle lifetime and the session's `expires_at`.

**Done when:** the same cookie used twice, 11 s apart, gives 200 then 401, the session shows `REFRESH_TOKEN_REUSE`, and the second 401 cleared the cookie.

#### `AllowedOrigins` (web layer, `common/security`)

- [ ] It answers whether an `Origin` header value is in the configured allowed origins (§2.5's property).
- [ ] A request with **no** `Origin` header is allowed (non-browser clients don't send one).

#### `AuthController.refresh` (via a workflow bean)

- [ ] An `Origin` that isn't allowed → 403 `ORIGIN_NOT_ALLOWED`, before any token is read.
- [ ] *Refreshed* → 200 with the new access token and the rotated cookie.
- [ ] *Rejected* → 401 `INVALID_REFRESH_TOKEN`, clearing the cookie unless superseded.
- [ ] A missing cookie → 401 `INVALID_REFRESH_TOKEN`.

| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/auth/refresh` | no body; the refresh cookie | **200** `{accessToken, tokenType: "Bearer", expiresIn}` + `Set-Cookie` (the new token) | 401 `INVALID_REFRESH_TOKEN` (+ a clearing `Set-Cookie`, except when superseded) · 403 `ORIGIN_NOT_ALLOWED` |

`INVALID_REFRESH_TOKEN` and `ORIGIN_NOT_ALLOWED` are codes in `user` (`UserErrorCode` or a new `SessionErrorCode`): your call.

### Traps ⚠️

- **Throwing inside the transaction undoes the detection.** The reuse branch revokes and records, then the request fails. If "fails" is an exception inside `@Transactional`, the revocation rolls back and no one ever knows.
- **The loser clears the winner's cookie.** Two tabs, one cookie: if the superseded 401 sends `Max-Age=0`, it deletes the token the winner just set.
- **Entity save on the session resurrects it.** Load the session, set `last_refreshed_at`, let Hibernate flush: it writes **every** column, including the `revoked_at = NULL` it loaded, over a concurrent log-out-everywhere (Phase 1's whole-row lesson, *verified* there). Use the conditional `UPDATE`.
- **`@Modifying` queries skip the persistence context** *(Phase 1)*: after a bulk `UPDATE`, an entity already loaded in this transaction still shows the old values. Don't load and bulk-update the same row in one transaction and then trust the entity.
- **Expired is not reused.** An expired token is just rejected; only a **consumed** token signals theft. Mixing them up revokes sessions of users who simply came back after two weeks.
- **The grace window compares against `consumed_at`,** the time the winner rotated, not the token's creation.
- **`Origin` absent ≠ `Origin` disallowed.** Rejecting missing `Origin` breaks curl and mobile clients; it adds nothing, because browsers always send `Origin` on cross-origin POSTs.
- **Refresh tokens accumulate**: one row per rotation, kept until the session ends (up to ~2,900 per active session per month). That's by design; Phase 9's cleanup job removes them with their expired sessions.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| 1. Replace the conditional consume with "find, check `consumedAt == null`, set it, save"; fire 20 parallel refreshes with one cookie | More than one 200, then a 500 from `uk_refresh_tokens_active` | Check-then-act loses; the index catches what the code missed |
| 2. Throw an exception from the reuse branch inside the transaction | 401, but the session is **still live** and no event exists | Detection must commit with a failing response |
| 3. Set the grace to 0 and fire two refreshes with one cookie at once | The second triggers reuse: the session is revoked and the **winner's** fresh token stops working | Why the grace window exists (the multi-tab wake-up) |
| 4. Clear the cookie on *superseded* too; repeat 3 with grace back at 10 s, using a browser-like cookie jar | The jar ends up empty although one refresh succeeded | The loser must not touch the winner's cookie |
| 5. Touch the session with an entity setter; refresh, and log out everywhere (§2.4) while a breakpoint holds the refresh before its flush | The session's `revoked_at` is `NULL` again | Whole-row writes undo concurrent changes |
| 6. `curl -H 'Origin: https://evil.example' -b jar -X POST …/refresh` | 403 `ORIGIN_NOT_ALLOWED`, cookie untouched | The cookie endpoints check who's asking |

### Build order

1. V6, the event types and recorder methods.
2. `SessionService.refresh` steps 1–3 and 6 (the happy path); the endpoint; refresh with the jar from §2.1.
3. Step 4 (expired, superseded, reuse). → *Deliberate failures 2, 3, 4.*
4. Step 5. → *Deliberate failure 5* (after §2.4's log-out-everywhere exists, or by SQL).
5. The `Origin` check. → *Deliberate failure 6.*
6. The race. → *Deliberate failure 1.*

### Tests

| Kind | Must prove |
|---|---|
| Unit: `SessionService.refresh` (mocks, `Clock.fixed`) | Each branch returns the right result: malformed, unknown, expired, superseded at grace − 1 s, **reuse at grace + 1 s**, dead session, success · reuse revokes with `REFRESH_TOKEN_REUSE` and records the event · superseded doesn't ask to clear the cookie |
| JPA slice | The conditional consume returns 1 then 0 · the conditional touch returns 0 for a revoked session |
| Integration | Login → refresh → 200, a **different** cookie, the old one consumed · old cookie again after the grace (adjustable clock) → 401, session revoked, `REFRESH_TOKEN_REUSED` in `security_events`, and the **new** token now fails too · the access token from before the reuse → 401 on the next request · 20 parallel refreshes with one cookie → exactly **one** 200, no 500 · disallowed `Origin` → 403 and nothing consumed · an expired bearer header on `/auth/refresh` doesn't matter |

---

## 2.4 — Logout, log out everywhere, and revocation on password events

### What we're building

A user can end **this** session (`POST /auth/logout`), **all** sessions (`DELETE /users/me/sessions`), or **one** chosen session from a list (`GET` / `DELETE /users/me/sessions/{id}`). A password **reset** ends every session; a password **change** ends every session except the one that made it. All of it takes effect on the next request, refresh or not.

- **In:** the three revocation paths, the session list, revocation inside `PasswordService`.
- **Out:** emailing the owner on reuse detection (not built; see "Explicitly NOT"), a cap on sessions per user, the cleanup job (Phase 9).

### How we're building it, and why

**Who can do what**

| Action | Authenticated by | Revokes | Reason | Event | Cookie |
|---|---|---|---|---|---|
| `POST /auth/logout` | the refresh **cookie** (works when the access token has expired) | the cookie's session, whatever the token's state | `LOGOUT` | none | cleared |
| `DELETE /users/me/sessions/{id}` | bearer | that session, if it's yours and live | `REVOKED_BY_USER` | `SESSION_REVOKED` | cleared if it's the current session |
| `DELETE /users/me/sessions` | bearer | all your live sessions, **including this one** | `LOGOUT_ALL` | `ALL_SESSIONS_REVOKED` | cleared |
| Password reset (`confirm`) | the reset token | all your live sessions | `PASSWORD_RESET` | (`PASSWORD_RESET` already recorded) | — |
| Password change | bearer + current password | all your live sessions **except** the current `sid` | `PASSWORD_CHANGED` | (`PASSWORD_CHANGED` already recorded) | — |
| Lockout | — | nothing ↺ D7 | — | — | — |

**The revocation itself** is one conditional bulk `UPDATE` per case (`… SET revoked_at = now, revoke_reason = ? WHERE user_account_id = ? AND revoked_at IS NULL [AND id <> ?]`), in the **same transaction** as the change that causes it (Phase 1: "a record commits with what it records"). Because every request checks its session (D5), nothing else is needed: no token deny-list, no `iat` comparison.

#### The choices

| Choice | Problem it solves | What it avoids |
|---|---|---|
| **Logout reads the cookie, not the bearer token** | Logging out works even with an expired access token, and lives under `/api/v1/auth/**` like refresh | A client that must refresh just to log out |
| **Logout is always 204** and always clears the cookie | Idempotent; a double-click or a dead session is still "logged out" | Clients handling errors from logout |
| **Logout revokes the session whatever the token's state** | A consumed (old) cookie still ends its family | A stale cookie that can't log out |
| **Session operations under `/users/me/sessions`** (bearer) | One owner, from the principal; the auth paths stay anonymous | An auth path that needs a bearer token, fighting §2.2's resolver rule |
| **Owner in the `WHERE` clause**: `id = ? AND user_account_id = ?` | Someone else's id simply matches no row → 404 | Load-then-check code that a later refactor forgets (IDOR) |
| **Revocation in the same transaction as the password write** | A reset that commits always kills sessions, and vice versa | A new password with the attacker's session still live, if the second step fails |
| **The list shows only live sessions, newest activity first, paginated, with `current: true` for the caller's `sid`** | "Which one is my laptop?" | Revoked noise; an unbounded list (every list is paginated, Phase 0 standard) |
| **Change keeps the current session** ↺ D7 | No pointless re-login after proving the password | — |

#### Alternatives we didn't take

| Alternative | Why not | The concrete problem it would cause later |
|---|---|---|
| Spring's `LogoutFilter` / `.logout()` | Built for server sessions and redirects | Its default success handler redirects (302) to `/login?logout`, and it runs before your rules: an API client gets HTML |
| Logout with the bearer token | Needs a live access token | After 15 idle minutes, "log out" first needs a refresh; a client that skips it leaves the session alive |
| Revoking in a separate transaction after the password change | Simpler code path | A failure between the two leaves "password changed" and the attacker logged in (exactly D7-C's failure) |
| 403 for someone else's session id | "It exists, but not yours" | Tells an attacker which session ids exist (the global sequence is guessable) |
| Recording an event for every plain logout | Completeness | Login history drowns in routine events; the session row already holds it |

### What we're optimising for

**Revocation that can't be skipped** (same transaction, same lookup on every request), **no IDOR by construction**, and **a logout that always works**.

### Concepts

💡 **IDOR (insecure direct object reference).** Any endpoint that takes an id must check the caller may touch **that** object. Putting the owner in the query makes the check impossible to forget. Phase 4 turns this into a permission evaluator.
💡 **Why "log out everywhere" includes this device.** It's the panic button. A user who presses it suspects compromise; leaving the current session alive is a guess that this device is clean.

### Requirements

#### `SessionService` (`user.session`): revocation and listing

- [ ] `revokeByRefreshToken(rawToken)` revokes the token's session with `LOGOUT` if live, and does nothing for an unknown or malformed token.
- [ ] `revokeOne(accountId, sessionId)` revokes with `REVOKED_BY_USER` only when the session is the account's and live, and records `SESSION_REVOKED`; it reports whether a row was revoked.
- [ ] `revokeAll(accountId, reason)` revokes every live session of the account in the caller's transaction (`MANDATORY`).
- [ ] `revokeAllExcept(accountId, sessionId, reason)` does the same, sparing one session.
- [ ] `listLive(accountId, currentSessionId, pageable)` returns live sessions sorted by `last_refreshed_at` descending, then `id` descending, marking the current one.
- [ ] Every revocation is one conditional `UPDATE`; none loads an entity.

#### `PasswordService`

- [ ] `confirmReset` calls `revokeAll(…, PASSWORD_RESET)` in its transaction.
- [ ] `applyChange` receives the caller's session id and calls `revokeAllExcept(…, PASSWORD_CHANGED)` in its transaction.

**Done when:** after a reset, every access token of that user gets 401 on its next request; after a change, only the changing session still works.

#### `AuthController.logout` and `SessionController` (`/api/v1/users/me/sessions`)

- [ ] Logout applies the `Origin` check, revokes through `revokeByRefreshToken`, clears the cookie, and returns 204.
- [ ] Revoking one session returns 404 `SESSION_NOT_FOUND` when no row was revoked.
- [ ] Revoking the current session (by id or all) also clears the cookie.
- [ ] Log out everywhere records `ALL_SESSIONS_REVOKED` once.

| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/auth/logout` | no body; the refresh cookie (optional) | **204** + clearing `Set-Cookie` | 403 `ORIGIN_NOT_ALLOWED` |
| GET | `/api/v1/users/me/sessions?page&size` | — | **200** page of `{id, createdAt, lastRefreshedAt, expiresAt, ipAddress, userAgent, current}` | 401 |
| DELETE | `/api/v1/users/me/sessions/{id}` | — | **204** (+ clearing `Set-Cookie` if current) | 401 · 404 `SESSION_NOT_FOUND` (unknown, not yours, or already ended) |
| DELETE | `/api/v1/users/me/sessions` | — | **204** + clearing `Set-Cookie` | 401 |

↺ D8: without the list, only `POST /auth/logout` and `DELETE /users/me/sessions` remain.

**Done when:** log in twice (two jars = two devices), list → two sessions, one `current`; delete the other → its bearer token gets 401 and its refresh fails; log out everywhere → both dead.

### Traps ⚠️

- **Someone else's session id must be a 404, indistinguishable from a missing one.** Session ids come from a global sequence: they're guessable.
- **Revoking the current session from `/users/me/...` must clear a cookie whose `Path` is `/api/v1/auth`.** A `Set-Cookie` can name any path, whatever the request's path; the clearing cookie must repeat the same name and `Path`, or the browser keeps the original.
- **A bulk revoke inside `applyChange` after the entity save:** the order inside the transaction doesn't matter for correctness, but the entity must not be re-saved after a `@Modifying` query that touched `user_accounts` (it doesn't here: sessions are another table). Keep it that way.
- **`revokeAll` with `MANDATORY`**: called outside a transaction it throws, by design (Phase 1's `MANDATORY` lesson: a revocation that commits separately is the bug).
- **Self-invocation** *(Phase 1, hit)*: calling `revokeAll` through `this` inside `SessionService` skips the proxy and its `MANDATORY` check.
- **`DELETE /users/me/sessions` vs `DELETE /users/me/sessions/{id}`**: a trailing slash or an empty id must not route to "delete all". Test `DELETE /users/me/sessions/` explicitly.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| 1. Look up the session by id only (no owner in the query); delete someone else's id | 204, and their device logged out | IDOR: the owner belongs in the query |
| 2. Revoke sessions in `PasswordWorkflow` **after** `confirmReset` returns, and make it fail (throw) | Password reset, attacker's session still live | Revocation commits with the change, or not at all |
| 3. Clear the cookie with a different `Path` | The browser (or jar) keeps the old cookie | Cookie identity is name + domain + path |
| 4. Pass a null `sid` to `revokeAllExcept` on change | **No session is revoked at all**, silently: `id <> NULL` is unknown in SQL, so the `WHERE` matches no row | SQL's three-valued logic. Assert the id isn't null before the query. *(Corrected 2026-10-06: my brief first predicted "logged out of the changing device"; that was wrong.)* |

### Build order

1. `revokeAll` / `revokeAllExcept` and the password-service calls. → *Deliberate failure 2*, then fix.
2. Logout. → *Deliberate failure 3.*
3. The list and revoke-one (D8). → *Deliberate failure 1.*
4. Log out everywhere.
5. Run the two-device scenario from **Done when**. → *Deliberate failure 4.*

### Tests

| Kind | Must prove |
|---|---|
| JPA slice | `revokeOne` with another user's id → 0 rows · `revokeAllExcept` spares exactly one · revoke on an already-revoked session → 0 rows and the original reason is kept |
| Integration | Two devices: revoke one → its access token 401 **and** its refresh 401; the other works · log out everywhere → both 401, cookie cleared, one `ALL_SESSIONS_REVOKED` event · reset → every session revoked with `PASSWORD_RESET` · change → others revoked with `PASSWORD_CHANGED`, the current works · logout without a cookie → 204 · logout with an expired access token header → 204 · another user's session id → 404 (same body as a random id) · `DELETE /users/me/sessions/` doesn't revoke all |

---

## 2.5 — CORS

### What we're building

A browser frontend on an allowed origin (dev: `http://localhost:3000`) can call the API: preflight requests are answered by the security chain, credentials (the refresh cookie) are allowed **only** on `/api/v1/auth/**`, and every other origin's scripts can't read responses.

- **In:** `CorsProperties`, the CORS configuration, `http.cors(...)`.
- **Out:** CSRF tokens (§2.3 explains why not), anything a non-browser client does (CORS doesn't apply to it).

### How we're building it, and why

1. **`CorsProperties`** (`taskflow.security.cors.allowed-origins`): a list of exact origins, validated at startup (non-empty, no `*`, each `scheme://host[:port]` with no path or trailing slash). §2.3's `AllowedOrigins` reads the same list.
2. **A `CorsConfigurationSource`** with two registrations, **auth first**: `/api/v1/auth/**` with credentials allowed; `/api/v1/**` without. Methods `GET, POST, PUT, PATCH, DELETE`; request headers `Authorization, Content-Type, X-Correlation-Id`; exposed headers `X-Correlation-Id, Location, WWW-Authenticate`; preflight cached for an hour.
3. **`http.cors(c -> c.configurationSource(source))`**: passed explicitly, so the chain's `CorsFilter` answers preflights **before** authentication and authorization.

#### The choices

| Choice | Problem it solves | What it avoids |
|---|---|---|
| **Exact origins from typed config** | Each environment lists its frontends | `*` (refused with credentials anyway, *verified*: `CorsConfiguration.validateAllowCredentials` throws) or reflecting any `Origin` (any site reads authenticated responses) |
| **Credentials only on `/api/v1/auth/**`** | The cookie is only needed there | Credentialed CORS on every endpoint for no reason (least privilege) |
| **Registration order: auth first** | The specific path wins | The first match wins (*verified*: `UrlBasedCorsConfigurationSource` returns the first registered match); `/api/v1/**` first would swallow `/api/v1/auth/**` |
| **The source passed explicitly to `http.cors`** | No dependence on a bean **name** | `CorsConfigurer` looks up a bean named exactly `corsConfigurationSource` (*verified*); a differently named bean is silently ignored |
| **Exposed `X-Correlation-Id` and `WWW-Authenticate`** | The frontend can show the correlation id and react to `invalid_token` | Scripts can't read non-safelisted response headers unless exposed |

#### Alternatives we didn't take

| Alternative | Why not | The concrete problem it would cause later |
|---|---|---|
| CORS only in Spring MVC (`WebMvcConfigurer.addCorsMappings`) and no `http.cors()` | MVC runs **after** the security chain | Every preflight hits `/api/v1/**` `authenticated()` with no credentials → 401 → the browser reports a "CORS error" for every call |
| `allowedOriginPatterns("*")` with credentials | Accepted by Spring, reflects any origin | Any website can make a logged-in user's browser call refresh and **read** the new access token |
| Same-origin deployment (a reverse proxy serving frontend and API on one origin) | No CORS at all | It's a deployment decision (Phase 11); the API should still work cross-origin in dev |

### What we're optimising for

**Least privilege** (exact origins, credentials only where the cookie lives) and **no silent misconfiguration** (explicit wiring, validated config).

### Concepts

💡 **A preflight** is an `OPTIONS` request the browser sends before a "non-simple" request (a JSON body, an `Authorization` header). It carries no credentials, so if it reaches an authenticated rule, it fails.
💡 **CORS protects the *reading* of responses, not the server.** A disallowed origin's request may still reach the server (simple requests aren't preflighted). That's why §2.3 also checks `Origin` on the endpoints that change state from a cookie.

### Requirements

#### `CorsProperties` (`taskflow.security.cors`, `common/security`)

- [ ] `allowed-origins` is a non-empty list, with no `*` and no trailing slash or path in any entry.
- [ ] `dev` sets `http://localhost:3000`; `prod` reads it from an environment variable.

#### The CORS configuration and `TaskflowSecurityConfig`

- [ ] `/api/v1/auth/**` is registered first, with credentials allowed; `/api/v1/**` second, without.
- [ ] Methods, request headers, exposed headers and max age are as listed above.
- [ ] The chain enables CORS with this source passed explicitly.

| Request | Expected |
|---|---|
| Preflight `OPTIONS /api/v1/organizations` from `http://localhost:3000` asking for `GET` + `Authorization` | 200, `Access-Control-Allow-Origin: http://localhost:3000`, no `Allow-Credentials` |
| Preflight `OPTIONS /api/v1/auth/refresh` from the same origin | 200, `Access-Control-Allow-Credentials: true` |
| Preflight from `https://evil.example` | 403, no `Access-Control-Allow-Origin` |
| `GET /api/v1/organizations` with a token and `Origin: http://localhost:3000` | 200 with `Access-Control-Allow-Origin` and `Access-Control-Expose-Headers` containing `X-Correlation-Id` |

**Done when:** the table passes with `curl -i -X OPTIONS -H 'Origin: …' -H 'Access-Control-Request-Method: …' -H 'Access-Control-Request-Headers: authorization'`.

### Traps ⚠️

- **CORS errors in the browser are often 401s.** The console says "CORS"; the network tab shows a 401 preflight. Check the status first.
- **The bean-name lookup** (*verified*): rely on it and a renamed bean silently disables CORS. Pass the source explicitly.
- **Order of registrations** (*verified*): first match wins.
- **`Origin: null`** is sent by sandboxed iframes and some redirects. It must never be in the allowed list.
- **curl ignores CORS.** A passing curl call proves nothing about browsers; the preflight table is the test.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| 1. Remove `http.cors(...)`, keep the configuration | Preflight → 401 | CORS must be handled inside the security chain, before authorization |
| 2. Register `/api/v1/**` before `/api/v1/auth/**` | The refresh preflight has no `Allow-Credentials`; a browser drops the cookie | First match wins |
| 3. Add `*` to the origins with credentials on | Startup refuses (your validation), or Spring throws per request without it | Credentials and wildcards don't mix |

### Build order

1. `CorsProperties` and its validation. → *Deliberate failure 3.*
2. The source and `http.cors`. → *Deliberate failures 1, 2.*
3. The curl table.

### Tests

| Kind | Must prove |
|---|---|
| Unit | `CorsProperties` rejects `*`, a trailing slash, a path, an empty list |
| Integration (real server: preflights and `Origin` need it) | Each row of the table above · a disallowed origin gets no `Access-Control-Allow-Origin` · the refresh preflight allows credentials and the organizations preflight doesn't |

---

## 2.6 — Side task: profile (from §1.7, decision 28) — first on the trim list

### What we're building

`GET /api/v1/users/me` returns the caller's own profile; `PATCH /api/v1/users/me` changes the display name and timezone. The response type decides what's public: never the hash, the lock state, the failure counter or the sessions.

### How we're building it, and why

- The caller is the `AuthenticatedUser` from §2.2; the account is loaded by its id (never by a path id: no IDOR possible).
- `PATCH` takes `{displayName?, timezone?}` where `null` means "leave unchanged". 💡 A record **can't tell "field missing" from "field sent as null"**; a real PATCH needs JSON Merge Patch or `JsonNullable`. Not now; write the limit down in a comment.
- The account change goes through an entity method (`UserAccount` has `@DynamicUpdate`, Phase 1 decision 27), so it never rewrites the lock columns.

| Choice | Problem it solves | Alternative not taken · its later problem |
|---|---|---|
| Timezone validated against `ZoneId.getAvailableZoneIds()` | Only IANA **region** ids (`Asia/Kolkata`) | `ZoneId.of` alone accepts `+05:30`, `UTC+1`, `Z`: fixed offsets ignore daylight saving, so Phase 9's due-date reminders fire an hour off for half the year |
| `null` = unchanged | Simple and safe for two optional fields | Merge Patch now: a library and a concept for two fields |
| `displayName` rules = registration's (`@NotBlank` when present, trimmed, no minimum length) | One rule for one field | Different rules on create and update ("Li" accepted at signup, rejected on edit) |

### Requirements

#### `ProfileService` / `UserController`

- [ ] `GET /users/me` returns id, email, username, displayName, timezone, role, emailVerified, createdAt, and nothing else.
- [ ] `PATCH /users/me` changes only the fields sent non-null.
- [ ] A timezone that isn't an IANA region id → 400 with `field: timezone`.
- [ ] A blank display name → 400 with `field: displayName`.
- [ ] Email change is rejected as an unknown field or ignored, never applied (backlog, `PROJECT_CONTEXT.md` §6).

| Method | Path | Request body | Success | Errors |
|---|---|---|---|---|
| GET | `/api/v1/users/me` | — | 200 profile | 401 |
| PATCH | `/api/v1/users/me` | `{displayName?, timezone?}` | 200 profile | 400 `VALIDATION_FAILED` · 401 |

**Done when:** `PATCH {"timezone":"+05:30"}` → 400; `PATCH {"timezone":"Asia/Kolkata"}` → 200 and `updated_by` = the username.

### Traps ⚠️

- `ZoneId.of("+05:30")` succeeds. Validate against the region id set.
- Returning `UserAccount` "just for GET": the hash is one Jackson annotation away from the wire.
- A load-then-save without `@DynamicUpdate` would rewrite `locked_until` and the counter (Phase 1, *verified*). It's there; don't remove it.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| Validate with `ZoneId.of` only; send `+05:30` | 200, stored | Offsets aren't timezones |

### Tests

| Kind | Must prove |
|---|---|
| Unit | The timezone validator: `Asia/Kolkata`, `UTC` pass; `+05:30`, `UTC+1`, `Z`, `Mars/Olympus` fail |
| Integration | GET has no hash, lock or counter fields · PATCH with one field leaves the other unchanged · `updated_by` = username |

---

## 2.7 — Tests across the phase (you write them; estimates include them)

**New tools this phase**

| Tool | Use it for | Watch out |
|---|---|---|
| A **login helper** next to `TestUsers` that calls `/auth/login` and returns the access token and the refresh cookie | Every integration test that needs a caller | Hash the shared password once (bcrypt is slow on purpose, Phase 1) |
| **`authentication(new TaskflowAuthenticationToken(...))`** post-processor | Slice tests that need an `AuthenticatedUser` principal | **Not `jwt()`**: it builds `JwtAuthenticationToken` (*verified*) |
| **An adjustable `Clock` shared by the issuer, the decoder's timestamp validator and `SessionService`** | Expiry, grace and absolute-lifetime tests without sleeping | Owed since Phase 1 (§1.5's tests need it too); one bean, `@Primary` in test config |
| **Reading and sending cookies with `TestRestTemplate`** | Refresh and logout | Read `Set-Cookie` from the response headers; send `Cookie: name=value` yourself |
| **`ApplicationContextRunner`** | "prod without a key doesn't start", property validation | It starts only what you give it: add the properties class and your config |
| **Parallel requests in a test** (`ExecutorService` + a `CountDownLatch` to start them together) | The refresh race | Assert on counts (exactly one 200), never on which thread won |

**Mutation checks: plant each, the suite must go red**

| Plant this | Caught by |
|---|---|
| Validator set to audience only (expiry off) | decoder unit test: `exp + skew + 1s` passes |
| Converter skips the session check | integration: revoked session → still 200 |
| Resolver ignores nothing under `/auth` | slice/integration: expired header on refresh/login → 401 |
| Entry point only in `exceptionHandling` | integration: invalid token → body has no `code` |
| Conditional consume replaced by check-then-act | integration race: more than one 200 |
| Reuse branch throws inside the transaction | integration: session still live after reuse |
| Superseded clears the cookie | unit: superseded asks to clear |
| Grace check uses `created_at` instead of `consumed_at` | unit: grace boundary tests |
| `revokeOne` without the owner in the query | integration: other user's id → 404 |
| Reset without `revokeAll` | integration: old access token works after reset |
| Change revokes the current session too | integration: the changing device gets 401 |
| CORS registrations swapped | integration: refresh preflight lacks `Allow-Credentials` |
| Auditor still on `TaskflowPrincipal` | `created_by` assertion |
| `email` added to the claims | issuer unit test: no `email` claim |

📊 **Measure:** suite time and container count at the end of §2.2 and at the end of the phase (Phase 1 ended at 76 tests, 2 containers). If a third container appears, find the configuration difference (testing guide §7). Also: the per-request session lookup's plan with real rows (expect the primary key).

---

## Definition of done

- [ ] No `httpBasic` anywhere; a Basic header gets 401.
- [ ] Login → 200 with an access token in the body and an `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` cookie; `Cache-Control: no-store`.
- [ ] The decoded access token has the agreed claims and **no email**.
- [ ] `prod` without a signing key refuses to start.
- [ ] Expired, foreign-key, wrong-audience and `alg: none` tokens → 401 `INVALID_ACCESS_TOKEN` with a `ProblemDetail` body and `WWW-Authenticate`.
- [ ] Every 401/403 from the chain is a `ProblemDetail` with a `correlationId`.
- [ ] Refresh rotates the cookie; the old cookie after the grace window revokes the session, records `REFRESH_TOKEN_REUSED`, and kills the new token too.
- [ ] 20 parallel refreshes with one cookie → exactly one 200, no 500.
- [ ] Logout, revoke-one, log out everywhere, reset and change each make the affected access tokens fail **on the next request**.
- [ ] Another user's session id → 404.
- [ ] CORS: the preflight table passes; preflights never reach authentication.
- [ ] `created_by` = the username for bearer-authenticated writes.
- [ ] No access token, refresh token, email or password in the logs at INFO.
- [ ] Suite green; the Basic-based tests are migrated, not disabled.
- [ ] ⏰ **D9 + D10 raised:** the §1.3–§1.6 and §2.1 test debt is scheduled (Phase 3's start, or a catch-up block) and recorded.
- [ ] README updated: login, refresh, logout, the cookie, bearer usage with curl (`-c`/`-b` jar), and that Basic is gone.
- [ ] `PROJECT_CONTEXT.md` §5's Phase 2 row matches what was built (D1).
- [ ] Small commits, about one per sub-section, staged by file name.

---

## Deliberate failures: the whole phase at a glance

Each is referenced from its section's build order; the detail is there.

| § | Break | Expect |
|---|---|---|
| 2.1 | `email` claim, decoded by hand | The email in plain text |
| 2.1 | Unmasked session-start result, logged | A raw refresh token in the log |
| 2.1 | A second unconsumed token by SQL | `uk_refresh_tokens_active` |
| 2.1 | `prod` without a key | Startup refused |
| 2.2 | Validator replaced by audience only | An expired token → 200 |
| 2.2 | Resolver doesn't skip `/auth/**` | Login → 401 with a stale header |
| 2.2 | Entry point in one place only | An empty 401 body |
| 2.2 | `@AuthenticationPrincipal TaskflowPrincipal` | 500 |
| 2.2 | Auditor on the old type | `created_by = system` |
| 2.2 | `alg: none` token | 401 |
| 2.2 | Restart with the ephemeral key | 401, then refresh works |
| 2.2 | Session revoked by SQL | 401 on the next request |
| 2.3 | Check-then-act consume, 20 parallel | Several 200s, then a 500 |
| 2.3 | Throw inside the reuse branch | Session still live, no event |
| 2.3 | Grace 0, two concurrent refreshes | Logged out after a "successful" refresh |
| 2.3 | Superseded clears the cookie | Empty jar |
| 2.3 | Entity save on the session during log-out-everywhere | Revoked session resurrected |
| 2.3 | Foreign `Origin` | 403, nothing consumed |
| 2.4 | Session by id only | Another user's device logged out |
| 2.4 | Revocation after the reset transaction, failing | Attacker still in |
| 2.4 | Clearing cookie with another `Path` | Old cookie kept |
| 2.4 | Change with a null `sid` | Nothing revoked, silently (`id <> NULL`) |
| 2.5 | No `http.cors` | Preflight 401 |
| 2.5 | Registrations swapped | No credentials on refresh |
| 2.5 | `*` with credentials | Refused |
| 2.6 | `ZoneId.of` only | `+05:30` stored |

---

## Explicitly NOT in Phase 2

OAuth2 login / social login · Spring Authorization Server (microservices track) · MFA · a JWKS endpoint (nothing else verifies tokens yet) · **multi-key verification** (the rotation procedure is documented in D2, not built) · DPoP / token binding · encrypted tokens (JWE) · emailing the owner when reuse is detected (a good follow-up; not needed for detection) · a cap on sessions per user · remember-me, server sessions · the token/session cleanup job (**Phase 9**: expired sessions, consumed refresh tokens, 12-month-old events) · rate limiting on login and refresh (**Phase 10**) · API keys (**Phase 10**) · forwarded headers (**Phase 11**) · OpenAPI (right after this phase) · email change (backlog).

---

## 🎯 Interview questions: answer these *during* Phase 2

1. Walk me through a request with a bearer token, filter by filter. Where is the signature checked, and where is your session checked?
2. How do you revoke a JWT? What did you choose, and what did it cost?
3. Why do you need both an access token and a refresh token?
4. What is refresh-token rotation, and how does reuse detection catch a thief? What happens to the legitimate user?
5. Two tabs refresh at the same moment with the same cookie. What happens in your system, and why?
6. Where do you store the refresh token in a browser, and why not `localStorage`?
7. Cookies bring CSRF back. How did you protect the endpoints that read the cookie?
8. RS256 or HS256? When would HS256 be a real risk?
9. How would you rotate your signing key without logging anyone out?
10. What's in your access token, and what deliberately isn't?
11. A user changes their password. What happens to their other devices? To the device they used?
12. Why is an invalid token rejected even on a `permitAll` endpoint?
13. 401 vs 403 with bearer tokens; what's `WWW-Authenticate` for?
14. What does CORS protect, and what doesn't it protect? Why must it be configured in the security chain?
15. Your custom JWT validator accepted expired tokens. How?

---

## Session split (~7h)

| Session | Sections | Est. | Actual |
|---|---|---|---|
| 1 | §2.1: keys, V5, issuer, sessions, login | ~1h 30m | |
| 2 | §2.2: bearer authentication, Basic removed, JSON 401/403, tests migrated | ~2h | |
| 3 | §2.3: refresh, rotation, reuse, V6 | ~1h 30m | |
| 4 | §2.4 + §2.5: logout, sessions, revocation on password events, CORS | ~1h 30m | |
| 5 | §2.6 profile, README | ~30m | |

Over budget? **Trim in this order:** §2.6 profile → the session list and revoke-one (keep logout and log out everywhere) → the `Origin` check (keep `SameSite=Strict`). **Never trim:** rotation with reuse detection and its race test, the per-request session check, revocation on reset and change, JSON 401/403, `denyAll()` last and the access-table tests.

**Before session 1:** read the Spring Security reference page *OAuth 2.0 Resource Server → JWT* (the "How JWT Authentication Works" diagram), then `BearerTokenAuthenticationFilter` in `~/.m2`.

---

## Phase 2 notes (15 min at the end; these become interview stories)

**What I built:**

**What confused me:**

**What I learned:**

**Traps I actually hit:**
