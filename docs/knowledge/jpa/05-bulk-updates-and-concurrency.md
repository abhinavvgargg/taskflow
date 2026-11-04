# JPA 5. Changing rows safely under concurrency

> The pattern TaskFlow uses wherever two requests can touch the same row: **decide with the database, not with a value Java read earlier.** One conditional statement, its row count as the answer, and a constraint behind it. Sources: [SOURCES.md](SOURCES.md).

## Check-then-act is a race

"Load the row, check a field in Java, change it, save" lets two simultaneous requests both pass the check. Every concurrency bug in this project had that shape:
- **Phase 0:** 30 concurrent POSTs with one new slug → 1 × 201, 25 × 409 from the service check, and **4 × 500** from the unique constraint: the same logical error, two status codes depending on timing.
- **Phase 1:** two clicks on one verification or reset link could both use it.
- **Phase 2:** 20 parallel refreshes with one cookie. *Seen (§2.3 deliberate failure 1, check-then-act consume):* several 200s, then a 500. *Seen again (Phase 3 §3.0 mutation 4):* 10 of 20 were 401 instead of 19.
- **Phase 3:** a role change and an ownership transfer on the same member.

## One conditional statement; the row count is the decision

Put every assumption the decision depends on into the `WHERE`, and branch on the rows changed:

| Where | Statement (shape) | 1 row | 0 rows |
|---|---|---|---|
| Use a link once (P1) | `UPDATE UserToken SET consumedAt = :now WHERE tokenHash = :hash AND purpose = :purpose AND consumedAt IS NULL AND revokedAt IS NULL AND expiresAt > :now` | this request used it | used, revoked, expired or another purpose |
| Rotate a refresh token (P2) | `UPDATE … SET consumed_at = :now WHERE id = ? AND consumed_at IS NULL …` | this request won | another request won |
| Revoke a session (P2) | `UPDATE … SET revoked_at, revoke_reason WHERE id = ? AND user_account_id = :owner AND revoked_at IS NULL …` | revoked | not yours / already revoked → 404 |
| Change a role (P3) | `UPDATE … SET role = :new, updated_at, updated_by WHERE organization_id = :org AND user_account_id = :target AND role = :expectedCurrent` | changed | changed meanwhile → 409 `MEMBERSHIP_CHANGED` |
| Remove / leave / transfer (P3) | conditional `DELETE` / `UPDATE`, each repeating the role it assumes | done | 409 or 404 (§3.2) |
| Accept an invitation (P3 §3.3, brief) | `UPDATE … SET accepted_at WHERE id = :id AND email = :myEmail AND <open> AND expires_at > :now` | accepted | not yours / closed / expired → 404 |

Every way of ending a session has `revoked_at IS NULL` in its `WHERE`, so a second revocation (a logout after a reset) changes nothing and **the first reason is kept** (Phase 2).

**Why it works:** PostgreSQL row-locks a row being updated. A second `UPDATE` of that row waits for the first transaction to commit, then **re-checks its `WHERE` against the committed row**, and matches nothing if the condition no longer holds (READ COMMITTED, PostgreSQL's documented behaviour). *Seen:* 20 parallel refreshes → exactly one 200, no 500 (Phase 2); two simultaneous ownership transfers → exactly one 204 and one owner (Phase 3, 6 of 6 runs).

**What a re-check can't see: rows inserted after the statement started.** Two concurrent re-invites of one email both revoke the open invitation (the second waits, then finds it already revoked), but the second's `UPDATE` can't see the row the first just **inserted**, so its `INSERT` hits the partial unique index `uk_organization_invitations_open` → a generic 409 (*inferred from PostgreSQL's documented behaviour, not run*; §3.3 brief). Correct outcome, generic answer.

## The expected value is a free optimistic lock

`AND role = :expectedCurrent` does what `@Version` would: a write based on a stale read changes nothing and says so, at no cost.

*Seen (Phase 3 §3.2 deliberate failure 3, window widened in a scratch copy):* with the expected role, a demote racing a transfer answered 409 `MEMBERSHIP_CHANGED` (6/6). Without it, the demote **overwrote the brand-new owner** (6/6), and only the "has an owner" trigger stopped the commit, as a 409 naming `ck_organizations_has_owner` that explains nothing.

### Alternatives TaskFlow rejected, and why

| Alternative | Why not here |
|---|---|
| `@Version` on the lockout counter (Phase 1) | Concurrent wrong passwords collide → `OptimisticLockException` → 500s during a brute force, and retries that give up leave attempts uncounted. The bulk counter queries don't bump the version anyway |
| `@Version` on memberships (Phase 3) | The expected role is the version, free. `@Version` → 409 is Phase 5's lesson (issues) |
| `@Version` in `BaseEntity` (Phase 0) | Blanket versioning makes lookup and audit tables throw `OptimisticLockException` for reasons nobody expects |
| `SELECT … FOR UPDATE`, then decide in Java (Phases 1, 2) | Correct, but it holds the row lock across a round trip and Java code; under a burst, requests queue and hold pool connections (the dev pool has 5) |
| Load the entity, call a setter, let dirty checking flush (Phase 3) | The flush writes in Hibernate's order, and a stale entity overwrites a concurrent change |

## Counters are incremented in SQL

`SET failed_login_attempts = failed_login_attempts + 1` (Phase 1): two simultaneous failures can't both read 3 and write 4. A second statement applies the lock only where the count reached the limit and resets the count in the same statement; its row count tells exactly one request that **it** applied the lock, so `ACCOUNT_LOCKED` is recorded once.

## Insert-only entities: the stale write that can't happen

Hibernate writes **every column** of a changed entity ([02](02-persistence-context-and-flushing.md)). A session loaded before a concurrent "log out everywhere" would write `revoked_at = NULL` back at flush: the revoked session comes back to life.

**The pattern (Phase 2 D11, your approach; reused for memberships in Phase 3):** a factory, no setters, **every** mutable column `updatable = false`, and every change a conditional `@Modifying` statement (Hibernate's HQL `SET` ignores `updatable`, *verified* in 6.6.53's `BaseSqmToSqlAstConverter.visitSetClause`). *Seen (Phase 2 §2.3 deliberate failure 5):* an entity "touch" of a session was simply not written.

## What a bulk statement doesn't do for you

- **It skips JPA auditing,** so statements set `updated_at` (from the `Clock`) and `updated_by` (the caller's username) themselves. *Seen (§3.2 deliberate failure 5):* without it, `updated_by` still named the inserter.
- **It leaves loaded entities stale:** read the answer back with a projection ([02](02-persistence-context-and-flushing.md)); after losing a race, **re-read** with a scalar query before deciding what to say (Phase 2: was the winner's consume within the 10-second grace?).
- **It flushes pending entity changes first only if asked:** `@Modifying(flushAutomatically = true)`. The token, session and membership statements ask; the Phase 1 lockout statements don't (alone in `REQUIRES_NEW`, nothing pending).

## Turning 0 rows into the right answer

The row count says "no"; the service decides **which** no, and a race must never be a 500:
- **`leave`:** 0 rows and still a member → the owner (409 `OWNER_CANNOT_LEAVE`); no membership left → removed meanwhile (404).
- **Transfer step 2:** 0 rows → not a member of this organization → 404 `MEMBER_NOT_FOUND`; the exception rolls step 1 back. *Seen (§3.2 deliberate failure 2):* ignoring that count, the deferred trigger caught the ownerless organization at commit, as 409 `ck_organizations_has_owner` instead of the right 404.
- **A unique violation from a race is a 409:** a cheap service check for the common case, the constraint as the guarantee, both mapped to the same 409 (Phase 0). *Seen:* 30 POSTs → 1 × 201, 25 × 409 (service), 4 × 409 (constraint), zero 500s after the fix.
- **A partial unique index backs the code up:** a concurrent "resend" violates `uk_user_tokens_active`, and the workflow turns that into the normal 202 (Phase 1); two parallel consumes can't fork a refresh family (`uk_refresh_tokens_active`, Phase 2).

## Statement order matters with immediate constraints

A unique index is checked at **each statement**: Phase 3's transfer must demote **before** promoting. *Seen (§3.2 deliberate failure 1):* promote-first → 409 `uk_organization_memberships_one_owner` on every transfer. "At least one owner" is a **deferred** trigger, so the zero-owner moment between the two is allowed ([06](06-schema-constraints-and-invariants.md)).

## Proving it

- **"I couldn't reproduce it" isn't evidence.** *Seen (Phase 0):* 6 concurrent requests were all caught by the service check; the window opened at 30.
- **Release requests together and assert counts** (`Concurrently.run`, never "thread 3 won"); "no 500" is part of the contract; accept **every** correct refusal (two transfers at once: the loser gets 409 or 403 depending on whether the winner committed before it reached the gate).
- **A guard that matters only under a race needs a test that doesn't need the race:** call each conditional statement with a **stale** value and with **another organization's** member, expect 0 rows. *Seen:* §3.2 mutations 5 and 12 were caught only by that test.
- **Choose data where the condition is the only guard.** *What happened to us (§3.2, mutation 13 survived at first):* the outsider in the tests owned another organization, so step 2's `role <> 'OWNER'` excluded him anyway and dropping the organization from the `WHERE` went unnoticed. With a **plain member** of another organization, the mutated transfer tried to make that person owner of **their** organization. For each `WHERE` condition, ask which row **only** it excludes, and test that row.
- **A window too narrow to hit can be widened, in a scratch copy only:** 25 runs swept 0–240 ms never reached §3.2's window; a 300 ms planted pause showed both outcomes every time.

More on race tests: [testing 02](../testing/02-security-race-and-time-tests.md).

## Interview questions

1. **How do you make a link usable once, even with two clicks at once?** One `UPDATE … WHERE … AND consumed_at IS NULL`; exactly one request changes a row.
2. **Two admins change the same member's role at once?** Each `UPDATE` carries the role it read; the second waits on the row lock, re-checks, matches nothing → 409 "changed, reload".
3. **How can an entity save never undo a revocation?** Insert-only entities: every mutable column `updatable = false`, changes only through conditional bulk updates.
4. **How do you handle a race on a unique value?** Service check + database constraint, both → the same 409.
5. **Why not `SELECT … FOR UPDATE`?** It holds a lock across a round trip; one conditional `UPDATE` does it in one statement.
6. **How do you test a guard that only matters during a race?** Call the statement with the stale value the race would produce, then mutation-test the guard.
