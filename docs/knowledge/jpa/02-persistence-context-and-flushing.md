# JPA 2. The persistence context: when the database actually does something

> Most of Phase 1's silent bugs came from not knowing **when** the database does something. This page is about that timing: the persistence context, flush, commit, and the cases where Hibernate's picture and the database's disagree. Sources: [SOURCES.md](SOURCES.md).

## The persistence context is a cache of entities

Inside a transaction, Hibernate keeps every entity it has loaded or saved in the **persistence context** (first-level cache), one Java object per row. `findById` looks there **before** the database, so "save, then `findById`" hands back the very same object with no SQL at all. That's why a repository test proves nothing about the database unless it flushes and clears first ([09](09-testing-jpa.md)).

## `save()` isn't a write; flush isn't a commit

- `repository.save(entity)` only puts the object in the persistence context. The SQL `INSERT` is sent at the **flush**, normally when the `@Transactional` method returns.
- After the flush the row still isn't permanent: other connections can't see it, and it can roll back. Only the **commit** makes it real.

*What happened to us (Phase 1 §1.3):* two people registering the same email at once. The unique constraint stops the second, but with `save()` the error appeared **after** the method returned, outside the `try` meant to turn it into a clean 409. `saveAndFlush()` sends the `INSERT` immediately, inside the `try`.

**Rule:** `saveAndFlush()` when a constraint error must surface where you can handle it. Never assume "flushed" means "saved".

## Dirty checking writes the whole row

When a loaded entity changes, Hibernate notices at flush (dirty checking) and writes an `UPDATE` that by default sets **every column** from the values loaded at the start of the transaction. Anything another statement changed meanwhile is overwritten with the old value.

*What happened to us (Phase 1 §1.6, tested on a copy):* a password reset had to unlock the account and set the new password.
1. Unlocking with the lockout's SQL query, then setting the password on the entity, **put the lock back**: the entity still remembered "locked".
2. The obvious fix, `clearAutomatically = true` on the query, detached the entity instead, so **the new password was never saved**.
3. Neither produced an error, and any "load, then save later" flow could erase a lock applied in between by a concurrent wrong guess.

**Rule:** `@DynamicUpdate` on `UserAccount` (only changed columns are written), and account changes go through entity methods, never a mix of SQL and entity changes in one transaction. For rows changed concurrently TaskFlow goes further: insert-only entities ([05](05-bulk-updates-and-concurrency.md)).

## Bulk statements bypass the persistence context

A `@Modifying` JPQL `UPDATE`/`DELETE` goes straight to SQL:
- **Entities already loaded keep their old values;** the persistence context isn't told.
- **No dirty checking, no `@PreUpdate`, no JPA auditing** ([07](07-auditing-and-time.md)).

Spring Data's two switches on `@Modifying`:
- `flushAutomatically = true`: flush pending entity changes **before** the statement so it sees them. The token, session and membership statements use it; the Phase 1 lockout statements don't (they run alone in their own `REQUIRES_NEW` transaction, with nothing pending).
- `clearAutomatically = true`: detach every loaded entity **after** the statement. It also throws away unflushed changes: that's how §1.6's password change was lost.

*What happened to us:*
- **Phase 2, refresh:** after losing the race to consume a token, the entity loaded before the race still said `consumedAt = null`; the loser re-reads with a **scalar query**.
- **Phase 3 §3.2:** the role-change response is read back with a **projection query** after the `UPDATE`; the role itself is always read as a scalar (`findRole`), never as an entity. (The §3.2 review had flagged a loaded membership entity as "harmless today, a trap tomorrow".)

**Rule:** after a bulk statement, don't trust any entity loaded earlier in the transaction; re-read with a query.

## Flush order isn't your call order

Hibernate orders the SQL it writes at flush from its own action queue, not from the order you called setters (*inferred*). That's why §3.2's ownership transfer, which must demote **before** promoting (an immediate unique index), uses two explicit `UPDATE` statements rather than two entity changes.

## `open-in-view=false` from day one

Boot defaults `spring.jpa.open-in-view` to `true`: the Hibernate session stays open for the whole HTTP request so lazy associations can be walked during serialisation. TaskFlow turns it off:
- **On:** N+1 queries happen during JSON serialisation, where no test looks, and a connection is held for the whole request, slow clients included.
- **Off:** the session closes when the `@Transactional` service method returns; touching a lazy association afterwards throws `LazyInitializationException`, loudly, in the first test that does it.

**Rule:** map entity → DTO **inside** the transaction (in the service). A consequence used in Phase 3: one transaction = one session, so "when the session opens" means "when the `@Transactional` method starts" ([08](08-multi-tenancy-with-hibernate.md)).

## Exceptions change type depending on the path

- Spring translates vendor exceptions into its `DataAccessException` hierarchy **at the repository proxy** and **at commit**. A duplicate through `repository.saveAndFlush` is a `DataIntegrityViolationException`; through `TestEntityManager.persistAndFlush` it's Hibernate's raw `ConstraintViolationException` (*verified*, Phase 0).
- The 409 handler catches `DataIntegrityViolationException`, which exists only because the call went through a repository or a transaction.
- `org.hibernate.exception.ConstraintViolationException` (a database constraint) and `jakarta.validation.ConstraintViolationException` (Bean Validation) share a name and are unrelated.
- A violation found **at commit** arrives differently again ([03](03-transactions.md)).

## Interview questions

1. **Flush vs commit?** Flush sends the SQL inside the open transaction: invisible to others and still revertible. Commit makes it permanent and visible.
2. **A reset unlocked the account, then the lock came back. How?** The unlock was a SQL update, the entity had been loaded before it, and Hibernate's update wrote back every column. `@DynamicUpdate` writes only changed columns; `clearAutomatically` lost the password change instead.
3. **What does `open-in-view=false` change?** Lazy access outside a transaction fails loudly instead of silently firing queries during serialisation and holding a connection for the whole request.
4. **Why didn't my `@Modifying` update show up in the entity I'd loaded?** Bulk statements don't touch the persistence context; re-read with a query.
5. **Why does the same duplicate key throw two different exceptions?** Spring translates at the repository proxy and at commit; Hibernate's own API doesn't.
