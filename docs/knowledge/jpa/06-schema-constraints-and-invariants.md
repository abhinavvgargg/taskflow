# JPA 6. Schema, constraints and invariants: the database as the last line of defence

> The application validates input, but the database has the final say: every path that writes data, including a hand-typed SQL fix or a feature not written yet, must pass its rules. Flyway, schema validation, and the constraints TaskFlow relies on. Sources: [SOURCES.md](SOURCES.md).

## Three layers of validation, on purpose

1. **Bean Validation at the boundary** catches malformed input cheaply ([spring 02](../spring/02-web-layer-validation-and-errors.md)).
2. **Business rules in the service** enforce what needs database state ("slug taken?", "is the target below you?").
3. **Database constraints** catch the race between check and write, and every path the service doesn't cover.

The layers can disagree, and that's a finding: Phase 0's `@Size(min = 3)` on a slug was stricter than the database `CHECK`, which accepts one character.

## Flyway only, `validate` as the drift guard

- **Migrations only; never `ddl-auto=update`.** `update` only ever *adds*: a rename leaves both columns, a deletion leaves the column forever, and there's no review trail.
- **`ddl-auto: validate`:** add a field without a migration and the app **refuses to start**, in CI, before deploy (*seen*, Phase 0). It works because Flyway runs **before** the `EntityManagerFactory` (Boot makes the EMF depend on the Flyway bean, visible in the stack trace).
- **What `validate` doesn't check:** nullability against the mapping (Phase 0's `insertable = false` passed and failed the first insert) and **primary keys** (Phase 1: `user_tokens` was first written without one). Constraints are reviewed by hand.
- **Applied migrations are frozen:** Flyway's checksum refuses any change, even a comment. Fix forward with a new version; undo is a paid feature.
- **`flyway.clean-disabled: true`:** one misconfigured job can't drop the schema.
- **Naming:** `V7__name.sql` with **two** underscores; with one, the file is silently ignored.
- **Every new table ships with its SQL verified in a rolled-back transaction** and a column-by-column table (type, purpose, why each constraint and index): the working agreement since 2026-09-27. V7–V9 were verified on a scratch copy of the dev database.

## Types

- **`timestamptz`, never `timestamp`** ([07](07-auditing-and-time.md)).
- **In PostgreSQL `text`, `varchar` and `varchar(n)` are the same type;** `(n)` is only a constraint. Increasing a length is instant; decreasing scans the table. Lengths are deliberate: `password_hash` is `varchar(200)`, sized for the longest algorithm we might move to, not bcrypt's 68 characters.
- **`inet` for IP addresses:** rejects non-addresses and stores one canonical form (`0:0:0:0:0:0:0:1` and `::1` compare equal, *checked on the dev database*).
- **Roles and statuses are `varchar` + `CHECK`** ([01](01-entities-and-mapping.md)).

## Named constraints, and their names in the API

- **Every constraint is named** (`uk_organizations_slug`, `ck_organization_memberships_role`); the 409 response carries the name as `constraint`.
- **PostgreSQL silently truncates names over 63 characters,** breaking code that matches them: keep names short.
- **`UNIQUE` is its own index:** PostgreSQL implements it as a unique B-tree index named after the constraint.
- **No constraint-name → error-code registry in `common`:** it would make `common` import every feature (Phase 0). Features translate their own constraint (Phase 1: `uk_user_tokens_active` → the normal 202).

## `CHECK` constraints catch what code forgets

Phase 1's: emails stored lowercase, usernames without `@`, password hashes with their `{id}` prefix, valid role names, token hashes of exactly 64 hex characters (so a raw token can't be stored by mistake), sensible combinations of event type and failure reason. Phase 3's V8 (brief): lowercase invitation emails, role never `OWNER`, `expires_at > created_at`. A `CHECK` sees one row only; rules about other rows need triggers (below).

*What happened to us (Phase 1, caught in review):* a `CHECK` compared two different clocks (a database time against a Java time).

## Foreign keys: an index and an `ON DELETE` rule each

PostgreSQL doesn't index foreign keys automatically, and without a delete rule, deleting a user fails while rows still point at them. TaskFlow's child rows (`user_tokens`, `security_events`, `user_sessions`, `refresh_tokens`, `organization_memberships`) are `ON DELETE CASCADE`: erasing a user really erases their data (Phase 1 decision 19).

## Partial unique indexes: "at most one, among these rows"

- `uk_user_tokens_active`: at most one **active** token per user and purpose (Phase 1 decision 12, your approach: revoke + this index beat "delete old tokens", because the database itself guarantees one live link and rows keep their history).
- `uk_refresh_tokens_active`: one unconsumed refresh token per session (Phase 2).
- `uk_organization_memberships_one_owner`: `(organization_id) WHERE role = 'OWNER'` (Phase 3).
- `uk_organization_invitations_open` (V8): one open invitation per organization and email.

**Cost of "revoke, don't delete":** revoked and consumed rows stay (and expired invitations) until Phase 9's cleanup job (`deleteExpiredBefore` exists for it). Accepted in Phase 1.

**Limit:** the predicate must be immutable, so "not expired" (`expires_at > now()`) can't be in it. That's why re-inviting **revokes** the open invitation before inserting (V8).

## Triggers for rules a `CHECK` can't express

- **Append-only `security_events`** (Phase 1 decision 21): a row-level trigger rejects every `UPDATE`, whoever sends it; `@Immutable` in Java ignores changes silently.
- **"Every organization has an owner"** (V7): a **constraint trigger**, `DEFERRABLE INITIALLY DEFERRED`, on organization inserts and membership updates/deletes. Checked at **commit**: a transfer may pass through zero owners between its two statements but never commit that. It also refuses, at commit, to delete a user who owns an organization (the cascade fires the trigger; *verified* on a scratch copy and by an integration test).
- **Its error is worded like PostgreSQL's own** (`… violates check constraint "ck_organizations_has_owner"`, `errcode = check_violation`), so Hibernate's PostgreSQL extractor finds the name and the 409 carries it ([03](03-transactions.md)).

## Deferred constraints and tests

A rolled-back test never commits, so a deferred check never runs: `OrganizationRepositoryTest` persists an ownerless organization and passes (with a comment saying why). To prove the invariant, flush then `SET CONSTRAINTS ALL IMMEDIATE` (*verified*; [09](09-testing-jpa.md)).

## What constraints don't cover (yet)

- **References between tenant-owned rows.** `@TenantId` guards reads and inserts, not references: `getReferenceById(otherTenantsId)` assigned to a relation goes through. **Composite foreign keys** close that from Phase 4 on; V7's `unique (organization_id, user_account_id)` is the first target ([08](08-multi-tenancy-with-hibernate.md)).
- **Deleting rows in a migration** was rejected for V7 (the 4 ownerless dev organizations stay): such a migration runs in every environment.

## Interview questions

1. **Why Flyway over `ddl-auto=update`?** Versioned, reviewable migrations; `update` guesses, never removes, and lets production drift.
2. **How do you stop someone forgetting a migration?** `ddl-auto=validate` refuses to start, in CI.
3. **Can you edit an applied migration?** No: the checksum refuses it. Fix forward.
4. **Does a unique column need an index?** No; `UNIQUE` is a unique index.
5. **How do you guarantee one active reset link per user?** A partial unique index on `(user, purpose) WHERE active`, with reissue revoking the old one in the same transaction.
6. **How do you guarantee exactly one owner?** A partial unique index for "at most one" (per statement) and a deferred constraint trigger for "at least one" (at commit).
