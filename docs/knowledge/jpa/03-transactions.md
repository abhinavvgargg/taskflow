# JPA 3. Transactions

> How `@Transactional` really works, how each TaskFlow method chooses its transaction, and the traps that roll back what should have survived (or commit what shouldn't). Sources: [SOURCES.md](SOURCES.md).

## `@Transactional` is a proxy, and `this` skips it

Spring implements `@Transactional` with a proxy around the bean. A call from another bean goes through the proxy, which starts or joins the transaction. A call from inside the same class (`this.method()`, **self-invocation**) goes straight to the object: **the annotation is silently ignored**.

*What happened to us (Phase 1):* a `MANDATORY` method called from inside its own class did nothing at all: no transaction, no error. **Rule:** a method that needs different transaction behaviour lives in a **different bean**. That's why flows mixing transactional and non-transactional steps are split into a *workflow* class and a *service* class ([spring 01](../spring/01-boot-configuration-and-beans.md) covers proxies in general).

## Boundaries live in services

- `@Transactional` on service methods, never controllers (`PROJECT_CONTEXT.md` §4).
- `readOnly = true` on queries: Hibernate skips dirty checking and doesn't keep snapshots.
- No remote calls inside a transaction (email, HTTP) and no deliberately slow work (bcrypt): the transaction holds a pooled connection the whole time.

## After a database error, the transaction is finished

If a database call inside a `@Transactional` method fails, Spring marks the whole transaction **rollback-only**. Catching the exception doesn't undo that: carry on, and the commit fails with `UnexpectedRollbackException`, a 500.

*What happened to us (Phase 1 §1.4):* two clicks on "resend" could both try to create a verification link; the second hit the "one active link" constraint. The first version caught that error **inside** the transaction and returned normally, which would have been a 500.

**Rule:** inside a transaction, catch a database error only to rethrow a different one. To carry on, catch it **outside**, after it rolled back: `RegistrationWorkflow` and `PasswordWorkflow` catch it, not the services.

## Propagation: how a method joins a transaction

| Propagation | Meaning | Where TaskFlow uses it |
|---|---|---|
| `REQUIRED` (default) | Join the caller's transaction, or start one | Most service methods |
| `MANDATORY` | Refuse to run without the caller's transaction (`IllegalTransactionStateException`) | Issuing/consuming tokens (P1); events that must commit with a revocation (P2) |
| `REQUIRES_NEW` | Pause the caller's transaction and run in a fresh one that commits on its own | The failed-login counter (P1) |

- **`MANDATORY` makes a missing transaction loud.** *Seen (Phase 2 §2.4 deliberate failure 2):* with the session revocation moved to a separate step after the reset, the reset committed, the `MANDATORY` step failed with `IllegalTransactionStateException`, the client got a 500, and **the old session kept working with the new password set**. The revocation belongs in the reset's own transaction.
- **`REQUIRES_NEW` costs a second connection** while the first is paused — one reason the login flow itself has no transaction.

## A record commits with what it records

- A **state change** (locked, verified, reset, revoked) and its event are saved in the **same** transaction: the event exists if, and only if, the change happened.
- A **failed attempt** (the lockout counter, `LOGIN_FAILED`) must survive the failure, so it's saved **on its own**.

## The login rollback trap

*What happened to us (Phase 1, deliberate failure):* with the failure counter written in the login's transaction, the failed login rolled back the count too. **The counter stayed at 0 and nothing ever locked**, with no error anywhere. Fix: the counter in `REQUIRES_NEW`, and **login itself not transactional**.

*Seen again (Phase 3 §3.0, mutation 8):* making `login` `@Transactional` lost the **history**, not the counter: the counter commits in its own `REQUIRES_NEW` transaction (still 4), while the `LOGIN_FAILED` events, which join the caller's transaction, rolled back (**0** instead of 4). The test asserts both. (The Phase 3 brief had said "the counter"; corrected.)

## Irreversible or slow work happens outside, from another class

An email can't be unsent. Sent inside the transaction, a failed commit leaves the user a link to something that doesn't exist (a "phantom email"), and the transaction stays open as long as the mail server takes.

*What happened to us (Phase 1 §1.4, caught in review):* the first version sent the verification email inside the registration transaction.

**Rule:** a class with **no** transaction (`RegistrationWorkflow`, `PasswordWorkflow`, `SessionWorkflow`, `MembershipWorkflow`) calls the transactional service, which commits when it returns, and only then does the irreversible or slow thing. An email that fails after the account is saved is logged (ids only) and the answer is still 201 (Phase 1 decision 15). Phase 9 replaces this with `@TransactionalEventListener(AFTER_COMMIT)`.

**The same shape for slow work (Phase 3 §3.2):** ownership transfer's password check is bcrypt (~100 ms on purpose). Inside the transaction it would hold one of the pool's few connections (the dev pool has 5); five slow transfers would make every other request wait. `MembershipWorkflow` checks the password first, outside any transaction.

## Return, don't throw, when the failure must be saved

An exception out of a `@Transactional` method rolls it back. When a rejection must **leave something behind**, return a result instead.

*Seen (Phase 2 §2.3 deliberate failure 2):* the refresh-token reuse branch revokes the session and records `REFRESH_TOKEN_REUSED`, then rejects. Throwing from that branch rolled both back: the session stayed live, no event. It returns a result; the controller turns it into the 401.

## Undoing a write without an exception: `setRollbackOnly()`

The opposite case: a rejection that must leave **nothing** behind, without an exception.

*What happened to us (Phase 2 D13, your approach):* a refresh that found its session dead had already consumed the token; committing that consume turned the client's later retry into a false theft signal. `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`, then return `SESSION_ENDED`:
- In the **outermost** transaction, the commit rolls back silently on return (*seen in a scratch run*; spring-tx 6.2.19 `AbstractPlatformTransactionManager.commit`).
- **Inside a caller's transaction**, the mark spreads to the caller, whose commit throws `UnexpectedRollbackException` (*standard behaviour, not run here*). So `refresh` must be called with no transaction around it (nit: `REQUIRES_NEW` would enforce it).

## A deferred constraint fails at commit, after your code returned

A deferred constraint (V7's "every organization has an owner" trigger) is checked at **commit**, after the `@Transactional` method returned: every `save()` succeeds, the method returns, and the request still fails.

*Seen (Phase 3 §3.1 deliberate failure 1):* with the owner membership removed from `create`, the request ended in 409 and no organization row existed.

*Seen (same run):* Spring's `JpaTransactionManager.doCommit` translates the commit failure by SQLState into a `DataIntegrityViolationException`, but the cause chain holds only the driver's raw `PSQLException` ("Unable to commit against JDBC Connection"), **no** Hibernate `ConstraintViolationException`, so the 409 said `constraint=null`. `CommonErrorUtility` now also reads the name from a raw `SQLException` with Hibernate's own PostgreSQL extractor (*verified*: `PostgreSQLDialect`'s extractor works from SQLState and message).

## Interview questions

1. **You caught the exception, so why did the transaction still fail?** A database error marked it rollback-only; catching doesn't clear that, so commit throws `UnexpectedRollbackException`. Catch outside the transaction to carry on.
2. **When `MANDATORY`, when `REQUIRES_NEW`?** `MANDATORY` for building blocks that must belong to a bigger transaction (a token in registration, an event recording a revocation). `REQUIRES_NEW` for records that must survive the caller's rollback (a failed-login counter), at the cost of a second connection.
3. **Why not send the email inside the transaction?** A failed commit leaves a link to nothing, and the transaction is held open for the mail server.
4. **Why does reuse detection return instead of throwing?** The revocation and event must commit with the 401.
5. **Why is login not transactional?** A failed login must not roll back its own failure record.
6. **Why does a `@Transactional` call within the same class get no transaction?** The transaction lives in the proxy; `this` bypasses it.
