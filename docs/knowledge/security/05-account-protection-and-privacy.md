# Security 5. Account protection, "no oracle", and privacy

> Not revealing who has an account, slowing guessing without punishing the owner, recovering an account safely, and keeping personal data out of places it leaks from. Sources: [../SOURCES.md](../SOURCES.md).

## No oracle: the same answer for every miss

Any difference in status, body or timing between "this exists" and "it doesn't" lets someone test a list of guesses. The rule (Phase 2 rule A12, Phase 3 D3) applies everywhere:
- **Login:** unknown email, wrong password and locked account → the same 401 `AUTHENTICATION_FAILED` with identical bodies (*proven*: §3.0 compares whole bodies minus `timestamp`, `correlationId`, `instance`); all three pay one bcrypt, so timing is close (a known email still does a few writes; the gap isn't measured). Unverified → 403 only after the right password ([02](02-password-authentication.md)).
- **Resend and reset requests always answer 202,** and send only to the address on file. Timing still leaks a little (a known email does database work and sends mail); Phase 9's async sending removes most of it.
- **Registration reveals a registered email on purpose** (409 `EMAIL_ALREADY_REGISTERED`, Phase 1 decision 7): clearer for someone who forgot their account; login and reset still never reveal it, and Phase 10's rate limiting blunts probing.
- **Ids are guessable** (one global sequence), so **another user's id, or another organization's, is the same 404 as a random id** — never 403. The owner (or tenant) goes in the query's `WHERE`, so "not yours" and "doesn't exist" are one code path ([jpa 05](../jpa/05-bulk-updates-and-concurrency.md)). 403 is right only inside a boundary you belong to ([07](07-tenancy-and-org-authorization.md)).
- **Accepted exception (§3.3 brief):** `409 ALREADY_MEMBER` tells an owner or admin whether a typed email belongs to a member of **their own** organization; the alternative (accept the invitation, refuse later) leaves pointless open invitations.
- **One error code for every bad token,** and for every refresh failure.

## Lockout

- **Policy (Phase 1 decision 18):** 5 wrong passwords → locked 15 minutes; the counter resets to 0 when the lock is applied; only wrong passwords count; success resets it.
- **Driven by Spring's authentication events,** so every password check counts (Basic in Phase 1, re-authentication now); login *history* is recorded by the login flow, because Basic authenticated every request and would have logged every call as a login. The listener reacts only to the **password** principal (every bearer request publishes a success event).
- **Correct under pressure:** incremented in SQL, its own `REQUIRES_NEW` transaction (a failed login's rollback can't erase it), a conditional statement applies the lock once ([jpa 05](../jpa/05-bulk-updates-and-concurrency.md)).
- **Fails loudly.** *What happened to us (Phase 1):* a broken query plus a listener that caught and logged database errors: every wrong password still got a 401, the counter never moved, nothing ever locked; only an ERROR line showed it. A counter that can't be written now fails the request (500) rather than letting an uncounted guess through.
- **Lockout can be used against the user** (anyone with your email can lock you out); the auto-unlock limits it; the real fix is per-IP rate limiting (Phase 10), which slows the attacker instead of the owner. Same for re-authentication: a stolen token can lock the owner out with wrong "current password" guesses.

## Password reset and change

- **Reset request:** always 202; a link (30 min, single-use, hashed, one live) to the stored address ([03](03-email-link-tokens.md)).
- **Reset confirm, in order:** validate the new password first (so a typo doesn't waste the link), then consume the link, then hash; save the change, the unlock (decision 23: mailbox control beats any guess), email verification if pending (decision 24) and the event **together**; revoke **all** sessions; email the owner afterwards (decision 26); don't sign them in.
- **Change:** requires the current password through the `AuthenticationManager` (400 `CURRENT_PASSWORD_INCORRECT`, counts toward lockout; decision 22); `PASSWORD_UNCHANGED` if equal; revokes all **other** sessions; cancels any outstanding reset link (or whoever requested it could take the account back); notifies the owner.
- **Email change** is designed but deferred (backlog): re-authenticate, confirm at the new address, revoke reset/verification tokens, notify the old address with a revert link.

## The security event log

`security_events` (Phase 1 decisions 6, 19–21): `inet` IP, a fixed list of event types, a failure reason only on `LOGIN_FAILED`, nothing stored for emails without an account, cascade-deleted with the user, 12-month retention (stance; Phase 9's job), append-only (`@Immutable` + a trigger). Events are saved with what they record ([jpa 03](../jpa/03-transactions.md)).

## Privacy in logs, claims and objects

- **Logs carry ids only;** never emails, passwords, tokens or cookies ([spring 04](../spring/04-observability-and-operations.md)).
- **Every type holding a secret masks it in `toString()`:** request records (password, token, email), properties, the signing key, issued tokens, login results; records print every component by default (*seen*: a plaintext password in the log).
- **No PII in token claims** ([04](04-jwt-refresh-and-sessions.md)); members don't see each other's emails (Phase 3 members list).
- **The client's IP is the connection's address** (`getRemoteAddr()`), never `X-Forwarded-For` (anyone can send any value). Behind a real proxy, trust forwarded headers only from it (Phase 11); Boot enables forwarded-header handling by itself on detected cloud platforms (*verified*). `InetAddress.getByName(null)` returns loopback, so a missing IP stays `null`.
- **The first admin is created by hand** (`UPDATE` on an existing account), never seeded in a migration (that ships a known admin password).
- **Test configuration lives in `src/test`:** *what happened to us (Phase 1):* `application-test.yml` with an admin test user sat in `src/main/resources`, one profile away from a known admin login in production.

## Interview questions

1. **Does your app reveal which emails have accounts?** Registration does, deliberately; login, resend and reset don't (same status and body; timing mostly).
2. **Why doesn't a locked account get a "locked" message?** Shown only after a right password, it would confirm the guess; so it's checked before and answered generically.
3. **Isn't lockout a denial-of-service risk?** Yes; auto-unlock limits it, rate limiting fixes it.
4. **Why 404, not 403, for someone else's resource?** Ids are guessable; 403 confirms existence. Owner in the `WHERE`, one code path.
5. **How do you get the client's IP?** The connection's address; forwarded headers only from a known proxy.
6. **How could a password end up in your logs?** A record's generated `toString()`; every record holding a secret overrides it.
