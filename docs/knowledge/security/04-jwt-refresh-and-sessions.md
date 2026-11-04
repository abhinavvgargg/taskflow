# Security 4. JWT access tokens, refresh tokens and sessions

> TaskFlow's Phase 2 design: a short-lived signed access token, a long-lived opaque refresh token in a cookie, and a session row that makes both revocable. The long-form explanation is `phase-2/JWT_AND_SESSIONS_GUIDE.md`; this page is the distilled reference. Sources: [../SOURCES.md](../SOURCES.md).

## The trap that defines the design

"JWT means stateless, so logout can't work, so delete the token on the client." Every case that matters (a stolen laptop, an XSS, a token pasted in a ticket) is one where **someone else** holds a copy. **Judge every design by what happens to the attacker's copy** (Phase 2 rule A1).

## Two credentials, two jobs

| | Access token | Refresh token |
|---|---|---|
| Purpose | Calls the API | Gets a new access token |
| Lifetime | 15 min | 14 days idle, inside a 30-day absolute session |
| Sent with | `Authorization: Bearer` on every request | Only `/api/v1/auth/refresh` and `/logout`, as a cookie |
| Format | JWT (signed) | Opaque: 32 random bytes |
| Server stores | Nothing (but see the session check) | **Only its SHA-256 hash** |
| Client keeps it | In memory | `HttpOnly` cookie JavaScript can't read |

The refresh token is **opaque**, not a JWT: it's checked in the database on every use anyway, and a JWT refresh token would need a deny-list to revoke. `expiresIn` is returned **in seconds**, so the client never decodes the JWT to schedule a refresh.

## The access token (JWT)

- **Header.payload.signature,** Base64URL-encoded: **signed, not encrypted** — anyone holding it reads the payload. *Seen (§2.1 deliberate failure 1):* an `email` claim was readable by anyone. **Claims carry ids, never PII:** `iss`, `aud`, `sub` (the user id), `sid` (session id), `preferred_username`, `role`, `iat`, `exp`.
- **RS256** (RSA 2048, Phase 2 D2): with HS256, anything that verifies can also mint tokens; with RS256 other services get only the public key. ES256 would need extra plumbing (Spring's builder takes an `RSAPublicKey`). **`kid` from day one** so keys can rotate with two valid at once; because refresh tokens are opaque, rotation logs nobody out (a token signed by a removed key gets 401, the client refreshes).
- **Keys:** private key only from the environment (never committed, logged, or printed by `toString()`); an **ephemeral pair in dev/test only** (per process: a restart or second instance invalidates every access token — *seen*); `prod` refuses to start without a key; the pair is checked at startup by comparing **moduli**. One key bean shared by encoder and decoder (two separately built dev keys = every token fails). `RsaKeyConverters` expects multi-line PEM; an escaped single-line env var fails (Phase 11).
- **JWT times are whole seconds:** truncate `iat` before computing `exp`, or the returned expiry and the token disagree. A one-element `aud` is written as a plain string (*seen*), which RFC 7519 allows.

## Verifying a bearer request (Spring's resource server)

Phase 2 D1: Spring's `oauth2ResourceServer().jwt()` with TaskFlow's own `JwtDecoder`, converter and entry point (a hand-written filter + provider is Phase 10's lesson). Facts (*verified* in 6.5.11 unless marked):
- **The algorithm is fixed on the server (RS256);** the token's `alg` is never trusted. `alg: none` → `PlainJWT` → rejected before validation (*seen*, §2.2 #6).
- **The signature is checked first;** nothing else runs on a token it rejects. `withPublicKey(...)` uses a single-key selector: the `kid` isn't even consulted.
- **`setJwtValidator(x)` replaces the defaults,** the only validator checking `exp` (*seen*, §2.2 #1: an audience-only validator accepted an expired token). Use `JwtValidators.createDefaultWithValidators(...)`, passing your timestamp validator **at the top level** (wrap it and a second default one appears).
- **A token with no `exp` passes the timestamp validator:** require `exp` explicitly (*your code caught it; the brief missed it*).
- **Clock skew:** default 60 s and `Clock.systemUTC()`; TaskFlow sets 5 s and the injected `Clock` (which is why moving the test clock expires tokens).
- **`BadJwtException` → 401 `invalid_token`; other `JwtException`s → 500;** a malformed header → 401; several tokens → 400 `invalid_request`.
- **The converter runs after decoding and validation:** it builds `AuthenticatedUser` from the claims, then does the **session check**. Database errors there propagate (an outage is a 500, never a 401).

## Revocation: one row, checked on every request

Phase 2 D5: every access token carries `sid`; after the stateless checks, **one primary-key lookup** asks "is session `sid` of user `sub` still live?" (`UserSessionRepository.isLive`, a boolean query). Revoking the session row kills every refresh token of that login and every access token **on the next request**. *Seen:* a session revoked by SQL → 401 at once (§2.2 #8; Phase 3 §3.0 test 1).
- **The honest cost:** verification is no longer purely stateless; a second service couldn't verify tokens without the database.
- **Why not the alternatives:** an `iat` cutoff per user can't log out one device; a deny-list of token ids grows forever and can't do "everywhere"; opaque access tokens lose signature-first rejection and the claims.
- **Every way of ending a login is one conditional `UPDATE`** with "not revoked yet" in its `WHERE` (the first reason is kept), and **the owner in the `WHERE`**: another user's session id → the same 404 as a random id (*seen*, §2.4 #1: without it, alice logged bob's device out).

## Rotation and reuse detection

- **Every refresh token works once:** using it consumes it and issues a new one in the same session (family). One conditional `UPDATE` decides the single winner of concurrent refreshes (*seen*: 20 parallel → one 200, no 500).
- **Reuse detection (D6):** presenting a token consumed **more than 10 s ago** means someone else has a copy; the server can't tell which side is the thief, so the whole session is revoked (`REFRESH_TOKEN_REUSE`) and `REFRESH_TOKEN_REUSED` recorded. Consumed rows are **kept** (deleted rows would make reuse look like an unknown token).
- **The grace window:** tabs waking together refresh at once with one cookie; a token consumed **less than 10 s** ago is *superseded*: 401, nothing revoked, cookie **not** cleared (the browser already holds the winner's). Measured from `consumed_at`, not `created_at` (§3.0 mutation 3a). Accepted cost: an attacker rotating within 10 s of the victim isn't detected.
- **Expired ≠ reused:** only a consumed token signals theft.
- **The absolute 30-day cap:** without it, a token stolen from an abandoned laptop could be rotated forever (the victim never presents the old one).
- **One live token per family** is enforced by `uk_refresh_tokens_active`.
- **Transaction shape** (detail in [jpa 03](../jpa/03-transactions.md)): the reuse branch **returns** (an exception would roll back the revocation); a refresh finding its session dead marks the transaction **rollback-only** so the consume doesn't commit (D13, your approach). After losing a race, re-read with a scalar query ([jpa 05](../jpa/05-bulk-updates-and-concurrency.md)).

## Session lifecycle

- **Login:** one clock reading; the session row and its first refresh token commit together; `LOGIN_SUCCEEDED` only after the session exists (a failed insert would leave lying history); login itself isn't transactional.
- **Logout** uses the cookie, not the bearer token (works after the access token expires), and is idempotent (always 204). **Log out everywhere** revokes every session, this one included, and clears the cookie.
- **Password events (D7):** reset → all sessions; change → all others (keep the current one); lockout → none (five bad guesses mustn't log the owner out everywhere); the revocation runs in the password change's own transaction (`MANDATORY`).
- **The session list** (`GET /users/me/sessions`) and revoke-one (`DELETE /users/me/sessions/{id}`) cover the lost-laptop case; the owner always comes from the principal.

## The refresh cookie

`HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` (D4). XSS can't read it; it travels only to the auth endpoints. Costs accepted: CSRF on two endpoints ([06](06-cors-and-csrf.md)), credentialed CORS on `/auth/**`, cookie jars in tools.
- **Cookie identity is name + domain + path:** clearing needs the same name and `Path`, so one class (`RefreshCookie`) builds both (*seen*, §2.4 #3: a clearing cookie with `Path=/` left the real one). A response from `/users/me/...` may clear a cookie whose `Path` is `/api/v1/auth`.
- **The loser of a race must not clear the winner's cookie** (*seen*, §2.3 #4).
- **Tools:** curl keeps `Secure` cookies over `http://localhost` and writes `HttpOnly` lines as `#HttpOnly_…` (grepping out `#` hides them, *hit*); in Postman, disable the cookie jar and send `Cookie: taskflow_refresh={{var}}` yourself, or the jar can't present an *old* token; the test client (JDK `HttpClient`) has no cookie jar at all ([testing 02](../testing/02-security-race-and-time-tests.md)).

## Interview questions

1. **What's in your access token, and what isn't?** Ids, username, role, session id, times; no email, because the payload is readable.
2. **RS256 or HS256?** RS256: with HMAC whatever verifies can mint.
3. **How do you revoke a JWT?** You can't change it; every request checks the session in its `sid` with one primary-key lookup.
4. **Why is the refresh token opaque and the access token a JWT?** The access token is verified without state on every request; the refresh token is looked up every time anyway, so it can be killed.
5. **Two tabs refresh at once with one cookie?** Row lock → one winner; the loser sees a token consumed milliseconds ago → superseded: 401, nothing revoked, cookie kept.
6. **How does rotation catch a stolen refresh token?** A consumed token presented after the grace proves a copy exists; the whole session dies.
7. **Your validator accepted expired tokens. How?** `setJwtValidator` replaced the defaults, and a token without `exp` passes the timestamp check.
8. **Why was an unexpired token rejected after a restart?** The dev key is per process; the signature check fails before expiry is looked at.
