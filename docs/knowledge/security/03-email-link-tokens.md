# Security 3. Tokens in email links (verification and reset)

> The single-use secrets sent by email in Phase 1, designed so a leaked database, log or forwarded mail is worth as little as possible. The same machinery (`TokenCodec`) hashes Phase 2's refresh tokens. Phase 3 deliberately has **no** token for invitations (D7, [07](07-tenancy-and-org-authorization.md)). Sources: [../SOURCES.md](../SOURCES.md).

## Six properties, each blocking a different attack

1. **Unguessable:** 32 random bytes from `SecureRandom`, never a UUID or `java.util.Random`.
2. **Stored only as a hash:** a leaked database holds no working links. A `CHECK` requires exactly 64 hex characters, so a raw token can't be stored by mistake.
3. **Expiring:** verification 24 h, reset 30 min (below).
4. **Single-use:** one conditional `UPDATE … SET consumed_at WHERE hash = ? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > :now`; exactly one of two simultaneous clicks changes a row ([jpa 05](../jpa/05-bulk-updates-and-concurrency.md)).
5. **Tied to one purpose:** a verification link can't reset a password (`purpose` in the `WHERE`; one `user_tokens` table, `purpose` in a `CHECK`).
6. **Cancelled when a new one is issued:** issuing revokes the active one, and a partial unique index (`uk_user_tokens_active`) allows one active token per user and purpose (Phase 1 decision 12, your approach: the database itself guarantees one live link, and rows keep their history).

Plus: never in a URL that reaches a server, never in a log outside dev, one error for every bad token (no hint about which tokens exist).

## SHA-256 for tokens, bcrypt for passwords

Slow hashing protects *guessable* secrets. A 256-bit random token can't be guessed at any speed, so a fast hash is enough, and the row must be **found** by its hash, which needs a deterministic hash (bcrypt's random salt makes lookup impossible).

## The token travels after the `#`

Links carry the token in the URL **fragment** (`#token=…`, Phase 1 decision 11): browsers never send it to a server, so it can't reach access logs, proxy logs or `Referer`. The frontend reads it and POSTs it; mail scanners that pre-open links can't consume it, because using it requires a POST.

## A link lives as long as its damage is acceptable

A verification link's worst case is a wrongly verified address: 24 hours. A reset link is a takeover credential: 30 minutes, enough for slow mail, short enough that an old link in a forwarded mail or backup is useless.

## Sending

Emails go out **after the transaction commits**, from a non-transactional workflow ([jpa 03](../jpa/03-transactions.md)). A failed send is logged (account id only) and the request still succeeds; resend is the recovery. Two quick resends can send two emails; only the newest link works (accepted). Concurrent resends that collide on `uk_user_tokens_active` become the normal 202. Revoked rows stay until Phase 9's cleanup job.

## Interview questions

1. **How would you design a reset or verification token?** 32 random bytes, only its SHA-256 stored, expiring, single-use, purpose-bound, revoked on reissue, carried in the URL fragment and POSTed back, never logged.
2. **Why SHA-256 here but bcrypt for passwords?** Tokens can't be guessed, and the row must be found by its hash.
3. **How do you make a token single-use under concurrency?** One conditional `UPDATE`; the row count decides.
4. **Why 30 minutes for reset but 24 hours for verification?** Lifetime matches the damage a stolen link can do.
