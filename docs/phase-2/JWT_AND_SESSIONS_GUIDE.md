# JWT & Sessions — Theory and Design Guide

> Companion to `PHASE_2_REQUIREMENTS.md` (what to build, the decisions D1–D9, traps and deliberate failures per sub-section) and, at Phase 2's close, `PHASE_2_LEARNING_LOG.md` (what was built and measured).
> This guide holds the **ideas and the reasoning** behind §2.1's design: what each concept means, why TaskFlow uses it the way it does, and what goes wrong otherwise. It contains no code on purpose; the code lives in the repository.
> Written 2026-10-06, while §2.1 was being implemented. It covers the JWT basics, the token lifecycle, the keys, and the classes of §2.1 (configuration, key handling, access-token issuing, sessions, refresh tokens, login, the cookie). §2.2–§2.4 appear only where §2.1's design is shaped by them.

**How to read the labels:**
- *Checked in the source* means it was confirmed in the jars in `~/.m2` (class present, behaviour read), not stated from memory.
- *Inferred* means it's standard framework behaviour stated from knowledge, not yet confirmed in this project. Each one says how to confirm it.

---

## Contents

1. [JWT in one page](#1-jwt-in-one-page)
2. [Signed, not encrypted](#2-signed-not-encrypted)
3. [Access token vs refresh token](#3-access-token-vs-refresh-token)
4. [Stateless vs stateful, and TaskFlow's hybrid](#4-stateless-vs-stateful-and-taskflows-hybrid)
5. [Rotation and reuse detection](#5-rotation-and-reuse-detection)
6. [The keys: private key, public key, key id](#6-the-keys-private-key-public-key-key-id)
7. [The whole lifecycle, end to end](#7-the-whole-lifecycle-end-to-end)
8. [The §2.1 building blocks, one by one](#8-the-21-building-blocks-one-by-one)
9. [Principles that run through all of it](#9-principles-that-run-through-all-of-it)
10. [Traps](#10-traps)
11. [Interview questions, with spoken answers](#11-interview-questions-with-spoken-answers)
12. [Open points to confirm in the run step](#12-open-points-to-confirm-in-the-run-step)

---

## 1. JWT in one page

A **JWT (JSON Web Token)** is a small piece of text the server hands to a client after login. The client sends it back on every request in the `Authorization: Bearer <token>` header, and the server uses it to know who is calling without asking for the password again.

It has three parts, joined by dots: **header . payload . signature**. Each part is Base64URL-encoded (a URL-safe text encoding, not encryption).

| Part | What it holds | TaskFlow's values |
|---|---|---|
| **Header** | How the token was signed | `alg` = `RS256`, `kid` = the key id, `typ` = `JWT` |
| **Payload** | The *claims*: facts about the caller and the token | `iss`, `aud`, `sub`, `sid`, `preferred_username`, `role`, `iat`, `exp` |
| **Signature** | Proof that the server made this exact header and payload | RSA signature over the first two parts, made with the private key |

**Registered claims** are names defined by the JWT standard, so every library understands them:
- `sub` (subject): who the token is about. TaskFlow uses the user **id** as a string, because ids never change and aren't personal data.
- `iss` (issuer): who made the token. A token from another issuer is rejected.
- `aud` (audience): who the token is meant for (`taskflow-api`). A token the same issuer made for some other service is rejected.
- `iat` (issued at) and `exp` (expiry): when it was made and when it stops working, in **whole seconds**.

**TaskFlow's own claims:**
- `sid`: the session id. It ties every access token to one row in `user_sessions`, which is what makes access tokens revocable (section 4).
- `preferred_username`: the app username, a standard OpenID Connect name. The auditor writes it to `created_by` without loading the user.
- `role`: `USER` or `ADMIN`, so authorisation needs no database lookup. It can be out of date for at most the access-token lifetime (15 minutes).

**Not in the token, on purpose:** the email (personal data), the password hash, the lock and verification flags. Section 2 explains why.

**What the server checks on every request** (§2.2, but the reasons shape §2.1):
1. The signature, with the algorithm **fixed on the server** to RS256. The `alg` written in the token's header is never trusted, because the classic JWT attacks are "`alg: none`" (no signature at all) and "algorithm confusion" (verifying an RS256 token as if it were HS256, using the public key as the secret).
2. `exp`, with a small allowance for clocks differing between servers (5 seconds in TaskFlow, see `JwtProperties`).
3. `iss` and `aud`.
4. That the session in `sid` is still live (TaskFlow's addition, section 4).

---

## 2. Signed, not encrypted

**What it means.** Anyone holding a JWT can **read** its payload: decoding Base64URL takes one command. What nobody can do without the private key is **change** it. If someone edits `"role":"USER"` into `"role":"ADMIN"`, the signature no longer matches the content and the server rejects the token.

A signature gives you two guarantees:
- **Integrity**: the content hasn't been changed.
- **Authenticity**: it was made by whoever holds the private key.

It gives you **no confidentiality**. That's a separate standard (JWE, JSON Web Encryption) that TaskFlow doesn't need.

**Why it matters in practice.** A token travels through browser tools, proxies, load balancers and log files. Whatever you put in a claim ends up readable in all of those places. A support engineer pasting a token into a ticket would leak an email address if the email were a claim. So the rule is: **no personal data and no secrets in claims**, and always HTTPS, because an intercepted token can be both read and replayed.

**Deliberate failure 1** in the requirements doc makes this concrete: add an `email` claim, log in, decode the middle part, and read the email in plain text.

---

## 3. Access token vs refresh token

| | Access token | Refresh token |
|---|---|---|
| **Purpose** | Calls the API | Gets a new access token |
| **Lifetime in TaskFlow** | 15 minutes | 14 days idle, inside a 30-day session |
| **Sent with** | Every API request (`Authorization` header) | Only `/api/v1/auth/refresh` and `/logout` (cookie) |
| **Format** | JWT (self-contained, signed) | Opaque random string (32 random bytes) |
| **Stored by the server** | No | Yes, **only its SHA-256 hash** |
| **Where the client keeps it** | In memory (a JavaScript variable) | `HttpOnly` cookie that JavaScript can't read |

**Why two tokens.** A self-contained token can't easily be taken back once issued. Making it short-lived limits the damage if it's copied. But a 15-minute login would be unbearable, so a second, long-lived credential exists only to get new access tokens, and *that* one is checked in the database every time it's used, so it can always be revoked.

**Why the refresh token is opaque, not a JWT.** A JWT refresh token would be self-contained, so revoking it would need a deny-list of every refresh token ever issued. The database lookup is the whole point of a refresh token, so there's nothing to gain from making it a JWT, and a stolen one would work for 14 days.

**Why only the hash is stored.** If the database leaked (a backup, a read replica, an SQL injection somewhere), raw tokens would be a list of working credentials. A SHA-256 hash can't be turned back into the token. Plain SHA-256 is enough here (no bcrypt) because the token is 32 random bytes: there's nothing to guess, unlike a human password. This is the same rule Phase 1 applied to email-verification and reset tokens, and the same `TokenCodec` does it.

**Why `expiresIn` is in the response.** The client schedules its refresh from `expiresIn` (seconds), so it never needs to decode the JWT to find `exp` and never depends on the token's internal format.

---

## 4. Stateless vs stateful, and TaskFlow's hybrid

**Stateful** (classic sessions): the server stores a session and gives the client an id. Every request looks the session up. Revoking is easy (delete the row), but the server holds state and every instance needs to see it.

**Stateless** (pure JWT): the token carries everything. The server only checks the signature and expiry and stores nothing. It scales easily, and any service with the public key can verify tokens, but **a token can't be revoked before `exp`**. Logout only deletes the client's copy, which protects the honest user and nobody else.

**The trap that defines Phase 2:** "JWT means stateless, so logout can't work, so we just delete the token on the client." Every case that matters (a stolen laptop, an XSS, a token in a log line) is one where **someone else** holds a copy. Each design choice is judged by what happens to the attacker's copy.

**TaskFlow's hybrid (D5).** Access tokens are checked statelessly first (signature, `exp`, `iss`, `aud`), so forged and expired tokens are rejected **without touching the database**. Then one primary-key lookup asks "is session `sid` of user `sub` still live?" Revoking the session row therefore kills:
- every refresh token of that login (they belong to the session), **and**
- every access token of that login (they carry its `sid`), **on the next request**, not 15 minutes later.

**The honest cost:** verification is no longer purely stateless. A second service couldn't verify tokens without database access. That's the trade-off to state in an interview: "JWTs can't be revoked by themselves; you choose between waiting for `exp` and a lookup. I chose the lookup, because logout, log-out-everywhere and password reset have to take effect immediately."

**Why D5 beats the other ways of revoking:**
- An `iat` cutoff per user (reject tokens issued before the last password change) costs the same lookup but can't log out one device or one session.
- A deny-list of revoked token ids costs the same lookup, grows forever, and can't do "log out everywhere" without storing every token ever issued.
- Opaque access tokens looked up in the database are close at this scale, but lose the signature-first rejection and the claims that avoid loading the user.

---

## 5. Rotation and reuse detection

These protect against **stolen refresh tokens**, which matter more than stolen access tokens because they live for days.

### Rotation

Every refresh token works **once**. Using it:
1. marks it consumed,
2. issues a **new** refresh token in the same session (family), and a new access token.

So each token is a single-use link in a chain that started at login.

### Reuse detection

A consumed token being presented again should never happen for a legitimate client, because the client always holds the newest token. So when it does happen, someone else has a copy.

```
Attacker copies RT1.
User refreshes with RT1      → gets RT2, RT1 is consumed
Attacker presents RT1        → already consumed → theft
                             → revoke the whole session (RT2 dies too)
                             → both are logged out; only the real user knows the password
```

The server can't tell which side is the thief, so it ends the whole family. The same logic works in the other order: if the attacker refreshes first, the user's later attempt is the reuse.

### Why the consumed row is kept, not deleted

If consumed tokens were deleted, a reused token would look exactly like an unknown token, and theft could never be detected. Keeping the row with `consumed_at` set is what makes detection possible.

### The grace window (D6)

Several browser tabs share one cookie. After a laptop wakes up, every tab's access token has expired, and they all refresh **at the same moment with the same cookie**. Strict detection would see the second request as theft and log the user out of everything, every morning.

So a token consumed **less than 10 seconds ago** (configurable, `reuse-grace`, 0 turns it off) counts as **superseded**: that request gets a 401, nothing is revoked, and the cookie is **not** cleared, because the browser already holds the winner's new cookie. A token consumed earlier than that is **reuse**: the session is revoked with reason `REFRESH_TOKEN_REUSE` and the event is recorded.

**The accepted cost:** an attacker who rotates a stolen token and has the victim present the old one within 10 seconds isn't detected. That window is narrow, and it's documented.

### Why there's an absolute session cap (D3)

Without a cap, an attacker who stole a refresh token from a laptop the victim no longer uses could rotate it **forever**: reuse detection never fires, because the victim never presents the old token again. The 30-day absolute lifetime kills every family, stolen or not; after it, a password is required.

### How the three lifetimes fit together

```
login at T0
├── session ends at T0 + 30d                      fixed, never extended
├── RT1 expires at min(T0 + 14d, T0 + 30d)
refresh at T0 + 10d
├── RT2 expires at min(T0 + 24d, T0 + 30d) = T0 + 24d
refresh at T0 + 20d
├── RT3 expires at min(T0 + 34d, T0 + 30d) = T0 + 30d    ← the cap wins
T0 + 30d: the session is over, whatever happens.
```

"Idle" means: if the user doesn't come back within 14 days of their last refresh, they must log in again.

### One live token per family, enforced by the database

The partial unique index `uk_refresh_tokens_active` allows at most one **unconsumed** token per session. A rotation bug or a race can never leave two live tokens in one family, whatever the Java code does. **Deliberate failure 3** shows the database refusing a second unconsumed row.

---

## 6. The keys: private key, public key, key id

| Piece | What it does | Who has it | Secret? |
|---|---|---|---|
| **Private key** | **Creates** signatures (mints tokens) | Only TaskFlow's token issuer | Yes, the most sensitive value in the system |
| **Public key** | **Checks** signatures | Anyone who verifies tokens | No, safe to publish |
| **Key id (`kid`)** | Says **which** public key verifies this token | Written in every token's header | No, it's a label |

### Asymmetric signing (RS256)

An RSA key pair is mathematically linked. A signature made with the private key can be checked with the public key, but the private key can't be worked out from the public key, and nobody can sign without it. Think of a wax seal: only you own the stamp, everyone knows what the seal looks like.

**Signing** (at login): the server hashes "header.payload" with SHA-256 and signs the hash with the private key.
**Verifying** (every request): the server reads `kid`, picks that public key, recomputes the hash, and checks the signature against it.

### Why RS256 and not HS256 (D2)

With HS256, **one shared secret both signs and verifies**. Anything that can verify tokens can also create them. The day a second service (Phase 11's compose setup, or the microservices track) needs to verify tokens, it would get the secret, and a leak there would let an attacker mint an ADMIN token. With RS256, other services get only the public key, which can't sign anything.

ES256 (elliptic curve) is equally secure and smaller, but Spring's decoder builder takes an `RSAPublicKey` directly and has no EC shortcut, so EC would mean extra plumbing for no security gain here. *(Checked in the source during the Phase 2 brief.)*

### Why the key id exists from day one

Keys get rotated: on a schedule, after a suspected leak, when someone with access leaves. During a rotation, two keys are valid at once, because tokens signed with the old key are still in circulation. The `kid` tells the verifier which key to use. Without it, the verifier would have to try every key, or a rotation would invalidate everyone at the same instant.

**Rotation procedure:** add the new key → sign with it → keep verifying with both → after the access lifetime (15 minutes) remove the old one. Because TaskFlow's refresh tokens are opaque database rows, not JWTs, rotation **logs nobody out**: an access token signed with a removed key gets a 401, the client refreshes, and receives a token signed with the new key. Phase 2 verifies with one key only; the multi-key procedure is documented, not built.

### What a leak of each one means

- **Private key:** a disaster. Anyone can mint valid tokens for any user and role. Generate a new pair, deploy it, remove the old key immediately, and review `security_events`.
- **Public key:** nothing. It's designed to be public (many systems publish theirs at `/.well-known/jwks.json`).
- **Key id:** nothing. It's in every token header already.

That's why only the private key gets special treatment: environment variables or secret files, never committed, never logged, redacted in every `toString()`.

---

## 7. The whole lifecycle, end to end

This is the TaskFlow version, with the refresh token in a cookie (D4).

### Phase ① Login (§2.1)

```
POST /api/v1/auth/login {email, password}
 1. authenticate()                          → 401 / 403 exactly as in Phase 1
 2. now = one clock reading
 3. start a session  (own transaction)      → user_sessions row + first refresh_tokens row (hash only)
 4. record LOGIN_SUCCEEDED
 5. sign an access token (sid = the new session id, iat = now)
 ← 200 {accessToken, tokenType: "Bearer", expiresIn: 900, user}
   Set-Cookie: <name>=<raw refresh token>; Path=/api/v1/auth; Max-Age; HttpOnly; Secure; SameSite=Strict
```

### Phase ② Each API request (§2.2)

```
Authorization: Bearer <access token>
 1. signature (RS256 only), exp (5 s skew), iss, aud     → no database involved
 2. is session `sid` of user `sub` live?                 → one primary-key lookup
 3. build the caller's identity from the claims          → no user load
```

### Phase ③ Refresh with rotation (§2.3)

```
POST /api/v1/auth/refresh   (the browser attaches the cookie; only to /api/v1/auth/*)
 1. malformed value → rejected, no query
 2. find the token by its hash → unknown → rejected
 3. consume it with a conditional update → 1 row: we won
                                         → 0 rows: expired / superseded / reuse (section 5)
 4. touch the session conditionally → 0 rows: session dead → rejected
 5. insert the successor (expiry capped at the session's end), sign a new access token
 ← 200 {accessToken, …} + Set-Cookie with the new refresh token
```

### Phase ④ Reuse (§2.3) → session revoked with `REFRESH_TOKEN_REUSE`, event recorded, every token of that login dead on its next use.

### Phase ⑤ Logout and friends (§2.4)

| Action | What gets revoked | Reason recorded |
|---|---|---|
| `POST /auth/logout` | This session | `LOGOUT` |
| `DELETE /users/me/sessions/{id}` | That session, only if it's yours (another user's id → 404, the IDOR rule) | `REVOKED_BY_USER` |
| `DELETE /users/me/sessions` (log out everywhere) | All your sessions, **including this one** (it's the panic button) | `LOGOUT_ALL` |
| Password reset | All sessions (account takeover recovery) | `PASSWORD_RESET` |
| Password change | All **other** sessions (you just proved you're you) | `PASSWORD_CHANGED` |
| Lockout | Nothing (otherwise five bad guesses would log the owner out everywhere) | — |

### Why the refresh token is a cookie and the access token is not (D4)

- **`HttpOnly`**: JavaScript can't read it, so an XSS can't steal the long-lived credential.
- **`Secure`**: sent over HTTPS only.
- **`SameSite=Strict`**: never attached to cross-site requests, which is the main CSRF defence.
- **`Path=/api/v1/auth`**: sent only to the auth endpoints, not with every API call.
- **`Max-Age`**: the browser deletes it when the token expires.

The access token stays in memory and goes in a header the client sets explicitly. Browsers never attach headers automatically, which is why CSRF protection stays off for bearer requests.

**Cookies, CSRF and CORS are three different things.** A cookie is attached by the browser automatically, which is what makes CSRF possible (another site makes the browser send a request carrying your cookie). `SameSite=Strict` stops the browser attaching it cross-site. CORS decides whether a script on another origin may **read** the response; it doesn't stop the request reaching the server. And "same site" isn't "same origin": `app.example.com` and `evil.example.com` are the same site, so §2.3 adds an `Origin` check on the two cookie endpoints.

**The costs D4 accepts:** CSRF comes back for exactly the two endpoints that read the cookie (handled by `SameSite`, the path and the `Origin` check), CORS must allow credentials on `/api/v1/auth/**` (§2.5), and `curl` needs a cookie jar.

---

## 8. The §2.1 building blocks, one by one

```
common/security                                user/session                         user
───────────────                                ────────────                         ────
JwtProperties ──► JwtKeyConfig                 SessionProperties                    LoginService
                   ├─ JwtSigningKey (bean)     UserSession, RefreshToken            LoginResult / LoginResponse
                   └─ JwtEncoder (bean)        SessionRevokeReason                  RefreshCookie
AccessTokenIssuer ◄┘                           UserSessionRepository                AuthController.login
  ├─ TaskflowClaims                            RefreshTokenRepository
  └─ IssuedAccessToken                         SessionService.start → StartedSession
```

`common` never imports a feature package. `AccessTokenIssuer` takes ids and strings, not a `UserAccount`, and §2.2's `SessionStatus` is an interface in `common` implemented in `user.session`, exactly like `ErrorCode`.

### 8.1 `JwtProperties` (`taskflow.security.jwt`)

**Job:** the typed, validated configuration for tokens: issuer, audience, access-token lifetime, clock skew, and the key (key id, private key, public key).

**The rules and why:**
- `issuer` and `audience` must be non-blank, so the app refuses to start rather than issuing tokens with empty claims that every verifier would reject.
- The access lifetime must be positive. Clock skew may be zero but never negative.
- The three key values are **all or none**. Two out of three is always a configuration mistake; catching it at startup beats a confusing failure at the first login. Blank values count as "not set", because an empty YAML entry binds as an empty string *(inferred: confirm by leaving `key-id:` empty and starting the app)*.
- **Null checks come before value checks.** Spring calls the record's constructor first and runs Bean Validation afterwards. A missing duration arrives as `null`; calling a method on it would throw a `NullPointerException` and hide the clear `@NotNull` message.
- **The private key never appears in `toString()`.** A record's generated `toString()` prints every component, so one log line or exception message containing the properties object would leak the key.
- **The keys are plain PEM text**, not key objects. Turning text into keys, checking they match and failing in prod belong to `JwtKeyConfig`; the properties class stays plain data.

**Where the keys come from (never from `application*.yml`):**
- **Environment variables.** Spring Boot's relaxed binding maps `TASKFLOW_SECURITY_JWT_PRIVATE_KEY` onto `taskflow.security.jwt.private-key` by itself. A `${…}` placeholder in YAML would be worse: a missing variable fails with "could not resolve placeholder" instead of `JwtKeyConfig`'s message naming the properties.
- **Secret files.** `spring.config.import: optional:configtree:/run/secrets/` turns each file in a folder into one property named after the file. That's how Docker and Kubernetes secrets are normally mounted.

**Clock skew of 5 seconds, not the default 60.** One application both issues and verifies, so the only skew is between its own instances. A 60-second allowance would let a token work a minute past its `exp`, and tests at `exp + 1s` would pass unexpectedly.

### 8.2 `JwtKeyConfig` and `JwtSigningKey`

**Job:** produce exactly one RSA key pair at startup and expose the `JwtEncoder` (signing) and, for §2.2, the public key (verifying).

**The decision it makes:**

```
key configured? ── yes ──► parse the PEM text, check public ↔ private, check ≥ 2048 bits
      │
      no
      │
profile dev or test? ── yes ──► generate a fresh 2048-bit pair + random kid, log one WARN
      │
      no (prod, staging, a typo, no profile at all)
      │
      ▼
refuse to start, naming the three properties and their env-var names
```

**Why an allowlist (dev, test) and not a blocklist (prod).** "Fail only in prod" would silently generate a throwaway key for a misspelled profile (`production`), a new `staging` profile, or no profile. Allowing the throwaway key only where it's known to be safe means every other case fails closed.

**Why the throwaway key is never allowed in prod.** Each instance would generate its own key. With two instances behind a load balancer (Phase 11), every other request would land on the instance that didn't sign the token → 401 → a refresh storm. And every restart would invalidate every token.

**Why a committed dev key is also rejected.** Private keys in a repository get flagged, copied and reused; a dev key quietly ends up in a prod config.

**One key object, shared.** The encoder and §2.2's decoder must use the **same** key pair. If each built or generated its own, then in dev two different random pairs would exist: tokens would sign fine and every verification would fail. Making the key a single bean that both receive guarantees one instance.

**Checking the public key belongs to the private key.** The two come from two separate environment variables, and pasting the public key of a different pair is an easy mistake. An RSA pair shares its *modulus* (public key = modulus + public exponent, private key = modulus + private exponent), so comparing moduli proves they belong together. Without the check, the app would start, sign tokens, and reject every one of them in §2.2 with a puzzling 401.

**At least 2048 bits.** Smaller RSA keys are considered breakable, and Nimbus refuses to sign with them anyway; failing at startup is clearer than failing at the first login.

**PEM formats.** The private key must be PKCS#8 (`BEGIN PRIVATE KEY`), the public key X.509 (`BEGIN PUBLIC KEY`). `openssl genpkey` writes PKCS#8 by default; an older PKCS#1 key (`BEGIN RSA PRIVATE KEY`) must be converted first. Parsing uses Spring Security's `RsaKeyConverters` *(checked in the source: present in `spring-security-core` 6.5.11)*, so no hand-written Base64 or `KeyFactory` code. Error messages name the property and never include the PEM text.

**The WARN line logs only the key id**, which is a label, never key material.

**`JwtSigningKey` redacts its `toString()`.** Nimbus's own key class prints itself as JSON **including the private parts**, so it's never a bean or a log argument.

### 8.3 `AccessTokenIssuer`, `TaskflowClaims`, `IssuedAccessToken`

**Job:** given user id, username, role, session id and "now", return a signed access token and its expiry.

**The logic:**
1. Truncate "now" to whole seconds → `iat`. Add the access lifetime → `exp`.
2. Header: RS256, the key id, `typ` = JWT.
3. Claims: exactly the eight in section 1, nothing else.
4. Sign with the `JwtEncoder`, return the token with `issuedAt` and `expiresAt`.

**Why each detail:**
- **"Now" is a parameter, not read from the clock inside.** `LoginService` reads the clock once and gives the same instant to the session (`created_at`) and the token (`iat`). Two readings would differ by milliseconds.
- **Truncating to seconds.** JWT stores `iat` and `exp` as whole seconds. Without truncation, the returned `expiresAt` would be `10:30:30.123` while the token says `10:30:30`, and the test "exp = iat + lifetime, to the second" would catch the mismatch.
- **`sub` and `sid` as strings.** The JWT standard defines `sub` as a string, OpenID Connect defines `sid` as a string, and strings avoid JavaScript losing precision on large 64-bit ids.
- **Role as a string, not the `UserRole` enum**, because `common` can't import the `user` feature.
- **The key id is set explicitly**, even though the encoder can add it by itself when there's only one key *(inferred)*, so the requirement "puts the key id in the header" is visible and testable.
- **Claim names live in one constants class**, shared with §2.2's converter, so a typo can't make the issuer and the reader disagree.
- **`IssuedAccessToken` redacts the token in `toString()`** and offers the lifetime in **seconds**, because `expiresIn` is seconds. `toMillis()` there would make clients wait 1000 times too long to refresh.

**Testing note.** The unit test checks the token's *content* by parsing and verifying it directly, not through a full decoder. A decoder validates `exp` against the system clock, so a test using a fixed "now" would start failing 15 minutes after the date written in the test.

### 8.4 `SessionProperties` (`taskflow.security.sessions`)

**Job:** the session lifetimes and the cookie attributes.

| Setting | Value | Rule |
|---|---|---|
| `refresh-token-idle-ttl` | 14 days | Required, positive |
| `absolute-ttl` | 30 days | Required, positive |
| `reuse-grace` | 10 seconds | Required, may be zero (zero = strict detection, D6's option B) |
| `cookie.name` | e.g. `taskflow_refresh` | Required; letters, digits, `_` and `-` only, because other characters break the `Set-Cookie` header |
| `cookie.path` | `/api/v1/auth` | Required; must start with `/`, otherwise browsers ignore it and fall back to the request's directory |
| `cookie.secure` | `true` | Defaults to `true` when absent; only `dev` may set `false`, and only if `curl` drops `Secure` cookies over `http://localhost` |
| `cookie.same-site` | `Strict` | Required; Spring Boot's `SameSite` enum *(checked in the source)*, so a typo fails at startup instead of producing a cookie the browser treats as `Lax` |

**Why it lives in `user.session`, not `common`:** only the user feature reads it.

**Why the lifetimes have no defaults:** the requirement says "required", and a forgotten value should stop startup instead of silently picking a lifetime nobody chose.

**Why `secure` defaults to `true` explicitly:** a plain boolean would default to `false` when missing, which is the unsafe direction.

**`SameSite=None` requires `Secure`.** Browsers silently drop `None` cookies that aren't `Secure`, so this combination is refused at startup.

**Nested validation needs `@Valid`** on the cookie block; without it, the constraints inside the nested record may not run.

**Naming note:** the nested cookie record is called `CookieSettings`, so it doesn't clash with the web-layer `RefreshCookie` class.

### 8.5 The entities: `UserSession` and `RefreshToken`

**`user_sessions`** is the family: one row per login. **`refresh_tokens`** holds each token of that family, by hash.

**Both extend `IdentifiedEntity`, not `BaseEntity`.** Login and refresh are anonymous requests, so `created_by`/`updated_by` would always say `system`. Domain-named timestamps say what happened instead: `created_at`, `last_refreshed_at`, `revoked_at`, `consumed_at`.

**Created by factories, from one clock reading.** The `UserSession` factory takes the account id, the client info, "now" and the absolute lifetime, and sets `created_at`, `last_refreshed_at` and `expires_at` from the same instant. That single reading is what makes the database checks `expires_at > created_at` and `last_refreshed_at >= created_at` safe (Phase 1 had to remove a similar check because it compared two different clocks). The `RefreshToken` factory takes the session, the hash, "now" and the already-capped expiry, and **asserts** the expiry doesn't outlive the session, so a forgotten cap becomes an immediate error instead of a token that outlives its session.

**Insert-only from Java.** Every change after the insert (consume, touch, revoke) is a **conditional bulk update** in a repository. Nothing is ever changed through a loaded entity, because Hibernate writes **every column** when it flushes an entity: a session loaded before a concurrent "log out everywhere" would write `revoked_at = NULL` back and resurrect the session (Phase 1's whole-row lesson, deliberate failure 5 of §2.3). So:
- no setters and no mutating methods;
- **every column is marked non-updatable**, a second safety net: even if someone adds a setter later, Hibernate leaves those columns out of its own updates. Explicit bulk updates still change them *(inferred: the JPA slice tests "consume returns 1 then 0" and "touch returns 0 for a revoked session" confirm it)*.

**`UserSession` stores the account id, not a link to `UserAccount`.** Every session operation works by id, and the foreign key already guarantees integrity; an association would add an unused proxy and cascade rules to get wrong.

**`RefreshToken` → `UserSession` is a lazy many-to-one** (Phase 1 decision 16): a real association, but loading a token doesn't drag in its session unless asked.

**No collection of tokens on the session.** Nothing walks a session's tokens in Java, the database enforces "one live token", and the collection would grow by one row per refresh for 30 days.

**Timestamps truncated to microseconds**, because PostgreSQL stores microseconds. On a JVM whose clock gives nanoseconds, the entity in memory would otherwise differ from the stored row, and equality assertions in tests would fail at random.

**`toString()` is written by hand**, never generated: no hash, no IP, no user agent (the requirement), and no access to the lazy session (which would throw outside a transaction).

**The IP address** is stored as PostgreSQL `inet`, which validates and normalises it. Parsing moved into `ClientInfo` so the session factory and the security-event recorder share one implementation; it accepts only IP literals and never does a DNS lookup.

**`SessionRevokeReason`** mirrors the database check: `LOGOUT`, `LOGOUT_ALL`, `REVOKED_BY_USER`, `PASSWORD_CHANGED`, `PASSWORD_RESET`, `REFRESH_TOKEN_REUSE`. All six exist from V5, so §2.3–§2.4 need no migration. A revoked session always has a reason and a live one never does, enforced by the database.

### 8.6 The repositories

**The core idea: conditional updates, decided by the row count.** Each change is one `UPDATE … WHERE <the state it expects>` that returns how many rows changed:
- **1 row**: you won, or the session was live.
- **0 rows**: someone else got there first, or the row is dead.

PostgreSQL makes the second of two concurrent updates on the same row **wait**, then re-check its condition after the first commits. So of 20 parallel refreshes with one cookie, exactly one gets 1 row.

**The alternative it replaces: check-then-act** ("find, check `consumedAt` is null, set it, save"). Two requests both read "unconsumed", both rotate, the family forks, and the partial unique index throws a 500 on the second insert (deliberate failure 1 of §2.3). `SELECT … FOR UPDATE` would also be correct, but costs two round trips and holds a lock while Java code runs.

**What each query expects:**
- **Consume a token:** its id, not yet consumed, not expired.
- **Touch a session:** its id, not revoked, not expired.
- **Revoke one session:** not revoked and not expired, so "1 row" means "a live session was revoked". The owned variant also requires the **account id**, which is what makes revoking another user's session impossible (IDOR by construction). It's a separate method so the guard can't be "optional".
- **Revoke all / all except one:** the account's sessions that aren't revoked yet. Every revoke requires "not revoked yet", so a second revoke changes 0 rows and **the original reason is kept** (a reset followed by a logout must still say `PASSWORD_RESET`).

**The per-request liveness check selects a boolean, never an entity**, because it runs on every request: one indexed lookup, no object building, no dirty checking. It also checks that the session belongs to the user in `sub`.

**The token lookup fetches its session in the same query**, because refresh needs the session's end (for the cap) and its account id (for the new access token). Both never change, so a later bulk update can't make them stale.

**Re-reading `consumed_at` after losing the race.** A losing refresh may have loaded the token **before** the winner committed, so its copy says "not consumed". If it trusted that copy after its update returned 0 rows, it would take the wrong branch or crash. So the refresh logic checks expiry first (that field never changes), then reads `consumed_at` with a fresh scalar query. Under PostgreSQL's default isolation (read committed), each new statement sees the latest committed data, so it gets the winner's timestamp and can tell **superseded** (within the grace window) from **reuse**.

**The session list returns a read-only projection**, not entities: plain data for the response, nothing that could be flushed by accident, and a `toString()` that hides IP and user agent. Its order (most recently refreshed first, then newest id) is fixed in the query; the caller passes an **unsorted** page request, because the project's `PageableFactory` always adds its own sort.

**Bulk updates bypass the persistence context.** After one, an entity already loaded in the same transaction still shows the old values. The rule: after a bulk update, read only fields that never change.

**Bulk updates need a transaction.** The repositories are only called from transactional services, never from controllers.

**A database outage must be a 500, not a 401.** Nothing in the liveness check catches database errors (Phase 1's lockout-listener lesson: a swallowing `catch` hid a broken query).

### 8.7 `SessionService.start` and `StartedSession`

**Job:** create one session and its first refresh token **together**, in their own transaction.

**The logic:**
1. Create and save the session from the account id, client info, "now" and the absolute lifetime.
2. Generate a raw token (32 random bytes, Base64URL), compute its expiry as **the earlier of** now + 14 days and the session's end.
3. Save only the **hash**.
4. Return the session id, the **raw** token, and the cookie's max age.

**Why it's its own transaction:** both rows commit or neither does, while `LoginService` stays non-transactional (section 8.8).

**Why the max age comes from the stored token**, not from the 14-day setting: near the 30-day cap the token lives **less** than 14 days. A cookie outliving its token would make the browser send a dead token for days, getting a 401 each time.

**Why the "earlier of" rule is a small separate function:** it's pure logic, unit-testable without mocks ("capped at the session's end"), and §2.3's successor token reuses it.

**`StartedSession` masks the token in `toString()`.** It holds a raw 14-day credential. **Deliberate failure 2** removes the mask and logs the result at INFO, putting a working credential in the console.

**`@Transactional` only works through the Spring proxy.** `LoginService` calls a different bean, so it's fine; calling `start` from inside `SessionService` itself would silently skip the transaction (Phase 1's self-invocation lesson).

### 8.8 `LoginService`

**Job:** unchanged authentication, then hand out two credentials.

**The order, and why it's that order:**
1. **Authenticate**, with Phase 1's failure handling untouched: wrong password and locked → 401 with the same body; unverified with the right password → 403; anything else (database down) → 500. Each failure is recorded and **no session is created**, because every failure branch throws before step 3.
2. **One clock reading.**
3. **Start the session.**
4. **Record `LOGIN_SUCCEEDED`.** After the session, so a failed session insert can't leave history saying "succeeded" for a login that returned 500.
5. **Load the account and issue the access token** with the new session id and the same "now". Username and role come from the loaded account, which also feeds the response body, so the claims and the body agree.

**Still not transactional.** Phase 1's rule: a failed authentication must not roll back the failure counter or the `LOGIN_FAILED` event. Wrapping `login` in a transaction "for consistency" would bring that bug back.

**The accepted edge case:** if recording the event fails after the session committed, the client gets a 500 and never receives the raw token. The session is live but unusable and expires by itself. The ordering rule guarantees only the opposite direction (no "succeeded" event without a session).

**Result types per layer, each masking its secrets:**
- `StartedSession` (service level): session id, raw refresh token, max age.
- `LoginResult` (service → controller): access token, started session, user.
- `LoginResponse` (the JSON body): `accessToken`, `tokenType: "Bearer"`, `expiresIn` in seconds, `user`.

One shared record across layers would tie the JSON shape to service internals, and one missing `toString()` override would leak a token.

### 8.9 `RefreshCookie` and `AuthController.login`

**`RefreshCookie`'s job:** be the only place that knows the cookie's attributes. It builds two cookies:
- **Set:** name, raw token, `HttpOnly`, `Secure` (from config), `SameSite` (from config), path, max age.
- **Clear** (§2.3/§2.4): the same name, path and attributes, empty value, max age zero.

**Why clear reuses the same builder.** A browser only deletes a cookie when the clearing cookie matches the original's name, path and domain exactly. A clearing cookie built by hand with a different path would leave the real one in place: logout would "succeed" and the token would stay.

**The controller** calls `login`, sets `Set-Cookie` from `RefreshCookie`, and returns `LoginResponse`.

**`Cache-Control: no-store` is not written by our code.** Spring Security's default headers already add `no-cache, no-store, max-age=0, must-revalidate` to every response *(checked in the source during the Phase 2 brief)*. Token responses must not be cached (RFC 6749 §5.1), so it's tested, not coded.

**A built cookie's text *is* the `Set-Cookie` header, raw token included.** It's sent, never logged.

---

## 9. Principles that run through all of it

**One clock reading per operation.** A login reads the clock once; the session's `created_at`, its `last_refreshed_at`, its `expires_at`, the refresh token's `created_at` and the access token's `iat` all come from that instant. That's what makes the database's time-ordering checks safe and the tests exact.

**Decide with the database, not with a read in Java.** "Is it still unconsumed?" and "is it still live?" are answered by the conditional update's row count, which is atomic. A read followed by a write leaves a gap for a race.

**Insert-only entities; changes through explicit queries.** Whole-row writes undo concurrent changes, so the entities can't be changed through Java at all.

**Enforce invariants where they can't be bypassed.** One live token per family (partial unique index), hash format (check constraint: 64 lowercase hex, so a raw token can never be stored by mistake), reason if and only if revoked, expiry after creation.

**Every object holding a secret masks it.** Records print every component by default. The properties, the signing key, the issued access token, the started session, the login result and the login response all override `toString()`.

**Fail closed, at startup.** Missing or inconsistent configuration (no key in prod, two of three key values, a blank issuer, a negative lifetime, `SameSite=None` without `Secure`) stops the application before it serves a request, with a message naming the property.

**`common` never imports a feature.** Shared building blocks take ids and strings; features implement `common`'s interfaces.

**Transaction boundaries are deliberate.** Login is not transactional (failure records must survive); session start is (two rows together); revocations join the caller's transaction (a revocation that commits separately from the password change is the bug).

**One profile mistake undermines the allowlist.** `application.yml` currently sets `spring.profiles.active: dev` as a default. A prod deployment that forgets `SPRING_PROFILES_ACTIVE` would therefore run as `dev`, and the key allowlist can't help, because the profile really is `dev`. Choosing `dev` in the IDE run configuration instead makes a missing profile hit the fail-closed branch. (Raised in chat on 2026-10-03; not yet decided.)

---

## 10. Traps

Each trap says what happens and the rule we follow. The per-sub-section traps and deliberate failures are also in `PHASE_2_REQUIREMENTS.md`.

- **Trusting the token's `alg`.** Tokens with `alg: none` or a switched algorithm get accepted by careless verifiers. Rule: the decoder accepts RS256 only.
- **Personal data in claims.** Anyone holding the token reads them. Rule: ids and roles only, never the email.
- **Deleting the token on the client and calling it logout.** The attacker's copy keeps working. Rule: revoke the session; every token carries `sid`.
- **Deleting consumed refresh tokens.** Reuse becomes indistinguishable from an unknown token, so theft is never detected. Rule: keep the row with `consumed_at`.
- **Strict reuse detection without a grace window.** Multi-tab wake-ups log users out every morning. Rule: 10-second grace, superseded requests don't clear the cookie.
- **Check-then-act on a token.** Concurrent refreshes fork the family. Rule: conditional update, branch on the row count.
- **Trusting an entity loaded before losing a race.** It says "not consumed" for a token that was just consumed. Rule: check expiry first, then re-read `consumed_at` with a fresh query.
- **Changing a session through an entity.** Hibernate writes the whole row and resurrects revoked sessions. Rule: no setters, non-updatable columns, explicit bulk updates.
- **Reading an entity after a bulk update.** It still shows the old values. Rule: after a bulk update, only read fields that never change.
- **A record without a masked `toString()`.** One debug log line leaks a 14-day credential. Rule: every type holding a token or key overrides it.
- **Logging a built cookie or the response headers.** The `Set-Cookie` text contains the raw token. Rule: never log them.
- **A clearing cookie with different attributes.** The browser keeps the real cookie. Rule: set and clear come from the same builder.
- **`expiresIn` in milliseconds.** Clients wait 1000 times too long. Rule: seconds.
- **Not truncating `iat`/`exp` to seconds.** The returned expiry and the token disagree by up to a second. Rule: truncate before signing.
- **Two clock readings in one operation.** Instants drift apart and time-ordering checks get fuzzy. Rule: one reading, passed down.
- **Making login transactional "for consistency".** A failed authentication rolls back the failure counter and event. Rule: login stays non-transactional; session start has its own transaction.
- **Recording `LOGIN_SUCCEEDED` before the session exists.** A failed insert leaves history that lies. Rule: session first.
- **Calling a `@Transactional` method through `this`.** The proxy is skipped and the transaction silently disappears. Rule: call it from another bean.
- **Encoder and decoder with separately built keys.** In dev, two random pairs exist and every token fails verification. Rule: one key bean, shared.
- **Mismatched public and private keys from config.** The app starts and rejects every token. Rule: compare moduli at startup.
- **"Fail only in prod" for the throwaway key.** A typo'd or new profile gets a throwaway key in production. Rule: allow it only in dev and test.
- **A `${…}` placeholder for the key in YAML.** A missing variable gives an unhelpful placeholder error. Rule: rely on relaxed binding and let `JwtKeyConfig` name what's missing.
- **The `PageableFactory` default sort on the session list.** It appends `createdAt` ordering behind the query's own order. Rule: pass an unsorted page request.
- **Catching database errors in the liveness check.** An outage turns into 401s and hides the real failure. Rule: let it propagate as a 500.
- **The default 60-second clock skew.** Tokens work a minute past `exp`. Rule: 5 seconds, with the injected clock.
- **Replacing the decoder's validators instead of adding to them** (§2.2). Setting only the audience validator turns off the expiry check. Rule: add issuer and audience to the defaults.

---

## 11. Interview questions, with spoken answers

**"Is a JWT encrypted?"**
"No, it's signed. Anyone with the token can decode and read the payload; the signature only stops them changing it. So I never put personal data or secrets in claims. If I needed confidentiality there's a separate standard, JWE, but for access tokens you don't usually need it."

**"How do you log someone out if JWTs are stateless?"**
"You can't revoke a pure JWT before it expires, so you choose between waiting for `exp` and a lookup. I put a session id in every access token and check that session with one primary-key lookup per request, after the signature and expiry checks. Revoking the session row kills the refresh tokens and the access tokens of that login on their next use. The cost is that verification isn't purely stateless any more."

**"Why have a refresh token at all?"**
"So the access token can be short-lived. If an access token is copied, it's only useful for a few minutes. The refresh token is long-lived but it's checked in the database every time, so it can always be revoked. And it's kept in an HttpOnly cookie, so JavaScript, and therefore an XSS, can't read it."

**"What is refresh-token rotation and why does it help?"**
"Each refresh token works once. Using it consumes it and issues a new one in the same family. That means a stolen token eventually gets presented after it was already used, which a legitimate client never does. When I see that, I revoke the whole family, so both the thief and the user are logged out and only the user can log back in."

**"What's the problem with strict reuse detection?"**
"Several browser tabs share one cookie. When a laptop wakes up, they all refresh at once with the same token, and the second one looks like theft. So I allow a ten-second grace window: a token consumed that recently is just rejected without revoking anything, and the cookie isn't cleared because the browser already has the new one."

**"Why RS256 instead of HS256?"**
"With HS256 the same secret signs and verifies, so anything that verifies tokens can also mint them. With RS256 only the issuer has the private key; other services get the public key, which can't sign. If a verifying service leaks, nobody can forge an admin token."

**"What's the `kid` for?"**
"It tells the verifier which key signed the token, so I can rotate keys without logging everyone out: I sign with the new key, keep verifying with both for the access-token lifetime, then drop the old one. Because my refresh tokens are opaque database rows, a rotation just causes one extra refresh."

**"Two requests refresh with the same token at the same time. What happens?"**
"Consuming the token is one conditional update: set consumed-at where it's still null and not expired. PostgreSQL makes the second update wait and re-check, so exactly one gets a row count of one and rotates. The other gets zero and re-reads the timestamp: if it was consumed within the grace window it's just rejected, otherwise it's treated as reuse. And a partial unique index guarantees there can never be two live tokens in one family, whatever the code does."

**"Why don't you update sessions through the entity?"**
"Hibernate writes every column when it flushes an entity. If I loaded a session and touched its last-refreshed time while another request revoked it, my flush would write revoked-at back to null and resurrect it. So the entities are insert-only, the columns are non-updatable, and every change is a conditional update decided by its row count."

**"Where do you keep the signing key?"**
"Never in the repository or the YAML. In production it comes from an environment variable or a mounted secret file, and the app refuses to start without it. In dev and test it generates a throwaway key at startup with a warning. The private key is redacted from every toString, and the config checks at startup that the public key actually belongs to it."

**"Why is login not transactional?"**
"Because a failed login has to leave records behind: the failure counter for lockout and the failed-login event. A transaction would roll those back with the exception. The session start has its own transaction instead, so the session and its first refresh token commit together."

---

## 12. Open points to confirm in the run step

| Point | Status | How to confirm |
|---|---|---|
| An empty YAML value binds as an empty string (the "all or none" key check relies on blank = not set) | Inferred | Leave `key-id:` empty, start in `dev`: no error, the WARN line appears |
| Non-updatable columns don't block explicit bulk updates | Inferred | JPA slice: consume returns 1 then 0; touch returns 0 for a revoked session; revoke sets the reason |
| The encoder adds `kid` by itself with one key | Inferred, and not relied on (set explicitly) | — |
| `curl -c` keeps a `Secure` cookie over `http://localhost` | Not verified (the requirements doc flags it too) | Log in with a cookie jar; if the jar is empty, set `cookie.secure: false` in `application-dev.yml` only |
| `Cache-Control: no-store` on the login response | Checked in the source; still to be tested | Integration test asserts the header |
| The default `dev` profile in `application.yml` | Open question | Decide whether to move the profile choice to the IDE run configuration |
