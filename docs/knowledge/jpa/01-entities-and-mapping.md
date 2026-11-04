# JPA 1. Entities and mapping

> How TaskFlow's entities are shaped, and why. Each lesson: what happens, *what happened to us* (when it did), and the rule now. *Seen* = observed in this project · *verified* = read in the framework source · *inferred* / *predicted* = reasoned, not run. Where every lesson came from: [SOURCES.md](SOURCES.md).

## Lombok stays off entities

Lombok's convenient annotations each break an entity in a different way:
- **`@EqualsAndHashCode` / `@Data`** build `equals`/`hashCode` from the fields, including the generated id. Before `persist` the id is `null`, after it isn't, so an entity put in a `HashSet` before saving can't be found in it afterwards.
- **`@ToString`** prints every field, including lazy associations. With `open-in-view=false` ([02](02-persistence-context-and-flushing.md)), a log line outside a transaction throws `LazyInitializationException`; inside one, it fires a query per association: an N+1 caused by logging.
- **`@Builder`** removes the no-argument constructor JPA needs to create instances.
- **Class-level `@Setter`** makes audit fields settable by any caller.

**Rule (Phase 0 decision 3):** on entities, at most `@Getter`. `@Slf4j` and `@RequiredArgsConstructor` are used everywhere else. DTOs are records, so Lombok doesn't apply to them.

## No `equals`/`hashCode` on entities, yet

Phase 0 deliberately writes none: Java's identity-based default is safe, while a naive field-based one breaks the hash contract above. The correct implementation (a stable business key, or id-based with a constant `hashCode`) is Phase 5's topic, when entities first go into sets and associations.

## Created through a factory, never half-built

Every entity has a `protected` no-argument constructor (for JPA only), a private constructor, and a static factory that checks its arguments: `Organization.create(name, slug)`, `OrganizationMembership.of(...)`, `UserSession.start(...)`, `UserAccount.createUserAccount(...)`. There are no public setters; changes go through methods that say what they do (`markEmailVerified`, `changePassword`, `resetPassword`).

*What happened to us:* `Organization` had a public no-arg constructor and setters from Phase 0 (a carried nit); in Phase 3 §3.1 it became `Organization.create`, so an organization can no longer exist without a name and a slug. In Phase 1 an entity read `Instant.now()` itself; review moved the time to the caller ([07](07-auditing-and-time.md)).

## Primary keys: a pooled sequence

- **`bigint` from a sequence, `increment by 50`, matched by `allocationSize = 50`** (Phase 0 decision 4). Hibernate fetches one sequence value and hands out 50 ids from memory, so inserts can be batched. If the two numbers disagree, instances hand out the same ids, and duplicate-key errors appear weeks later, far from the cause.
- **Why not `IDENTITY`:** the database assigns the id at insert, so Hibernate must run every insert immediately to learn it: a round trip per `persist`, and JDBC batch inserts silently turned off.
- **One sequence for every table** (`global_id_seq`, declared once in `IdentifiedEntity`; Phase 0 decision 7). Less repetition; the cost is that all tables share one number space, which also makes ids guessable (one reason every miss across a boundary is the same 404).
- **Gaps are normal.** Each restart abandons the rest of its block (ids 202–206 after a restart, not 1–5). Sequences guarantee uniqueness, never contiguity.
- **Taking a value by hand is safe.** `nextval('global_id_seq')` in a migration backfill (V7) or a hand-written `INSERT` never collides with Hibernate's pooled ids: a fresh value is never inside a block Hibernate already holds.

## Sharing mapping: `@MappedSuperclass`, not inheritance

`@MappedSuperclass` shares fields and mapping with no table of its own and no polymorphic queries; `@Embeddable` is a value object stored inside another table; `@Inheritance` is a real, queryable type hierarchy. TaskFlow has two levels:
- `IdentifiedEntity`: just the id. Used by append-only and insert-only rows (`SecurityEvent`, `UserSession`, `RefreshToken`) that have no meaningful `updated_*` columns.
- `BaseEntity extends IdentifiedEntity`: adds `created_at/by` and `updated_at/by` with JPA auditing.

*What happened to us:* the split came from Phase 1 decision 21: an append-only security log would otherwise carry `updated_*` columns that never change, and a second clock.

## Associations or plain ids

- **`@ManyToOne` loads eagerly by default.** Loading any `UserToken` would also load its `UserAccount`, every time: a quiet N+1 seed. Where an association is mapped, it's `fetch = LAZY` (Phase 1 decision 16).
- **Most TaskFlow rows hold a plain `Long` instead:** `SecurityEvent.userAccountId`, `UserSession.userAccountId`, `OrganizationMembership.organizationId` / `userAccountId`. Every operation on them works by id: the per-request session check needs no join, recording an event needs no entity load, and the `organization` feature doesn't map `user`'s entity. When a query needs both sides, it joins by entity name ([04](04-queries-projections-and-paging.md)).
- **Rule:** map an association when code navigates it; otherwise store the id.

## The signed-in user is not an entity

The principal lives in the `SecurityContext` for the whole request, outside any transaction (Phase 1 decision 4). An entity there would be a detached, stale copy of the row with lazy associations waiting to throw; having the entity implement `UserDetails` was rejected for that reason. TaskFlow's principals are small classes/records with ids and names (`TaskflowPrincipal`, `AuthenticatedUser`) — see [security 02](../security/02-password-authentication.md).

## Enums: stored as text, guarded by a `CHECK`

- `@Enumerated(EnumType.STRING)` on a `varchar` column, plus a `CHECK` listing the allowed values (`user_accounts.role`, `organization_memberships.role`). `ORDINAL` stores positions: reorder the enum and every stored row silently changes meaning.
- *Seen (Phase 1 mutation check):* switching `role` to `ORDINAL` made the app refuse to start: `ddl-auto: validate` found an integer mapping on a `varchar` column (32 test errors).
- A native SQL query is the way to prove what's stored; through JPA both forms look the same.
- **Not a PostgreSQL enum type:** adding a value later means `ALTER TYPE`, awkward inside Flyway's transactions. `varchar` + `CHECK` is the convention.

## Which annotations do something at runtime

Under `ddl-auto: validate`, Hibernate never generates DDL:
- **Runtime** (they change SQL): `sequenceName`, `allocationSize`, `insertable`, `updatable`, `@Enumerated`, `@Column(name)`.
- **DDL-only, inert under `validate`** (Phase 0 §0.5): `initialValue`, `unique`, `length`. The migration is the truth; these are documentation. (`nullable` isn't on the verified list: `validate` doesn't check it against the table; whether Hibernate checks it at runtime hasn't been looked at.)

*What happened to us (Phase 0):* `insertable = false` on `updated_at` sounded right ("it hasn't been updated yet"), but the column is `NOT NULL` with no default. Hibernate left it out of the `INSERT`, and the first write failed. `validate` didn't catch it: it checks that columns exist with compatible types, not nullability against the mapping.

## Database defaults don't reach Hibernate's inserts

Hibernate's `INSERT` lists every mapped column and sends unset ones as `NULL`; a `DEFAULT 'USER'` applies only when an `INSERT` leaves the column out, which Hibernate never does. **Rule:** defaults are set in Java (in the factory).

## `updatable = false` and insert-only entities

`updatable = false` removes a column from the `UPDATE` Hibernate writes when it flushes a changed entity. Two uses:
- On `created_at` / `created_by`, so an update can't rewrite who created a row.
- On **every** mutable column of rows that change concurrently (`UserSession`, `RefreshToken`, `OrganizationMembership`), making them **insert-only from Java**: all later changes are conditional `@Modifying` statements, which `updatable = false` doesn't affect (*verified*: Hibernate 6.6.53's HQL `SET` never checks updatability). Full reasoning in [05](05-bulk-updates-and-concurrency.md).

## `@Immutable` is silent

`@Immutable` (on `SecurityEvent`) tells Hibernate never to update the entity, but changes are **ignored silently**, not refused (Hibernate Javadoc). The append-only guarantee comes from a database trigger that rejects every `UPDATE` ([06](06-schema-constraints-and-invariants.md)).

## Smaller mapping facts

- **`user` is a reserved word in PostgreSQL**, and `User` is a Spring Security class: the table is `user_accounts`, the entity `UserAccount`.
- **`@Table` takes `name = …`.** *What happened to us:* `@Table("security_events")` didn't compile.
- **`InetAddress` maps to PostgreSQL `inet`** with no annotation.
- **A custom `toString()` lists ids only** (`UserSession`, `OrganizationMembership`): no lazy fields, no tokens.

## Interview questions

1. **Why is `@Data` dangerous on a JPA entity?** Its `equals`/`hashCode` use the generated id, which changes at persist, so the entity gets lost in hash sets; its `toString` walks lazy associations, so a log line can throw or fire queries.
2. **Why is `IDENTITY` a problematic id strategy at scale?** The database assigns the id during the insert, so Hibernate inserts immediately on every persist: a round trip each time, and no JDBC batching.
3. **Why are there gaps in my ids?** The pooled allocator reserves blocks of 50; a restart abandons the rest of the block. Sequences guarantee uniqueness, not contiguity.
4. **Association or plain id column?** Map the association when code navigates it, lazily. When code only works by id, a plain `Long` avoids joins and entity loads and keeps features decoupled.
5. **Why did a column marked `insertable = false` break the first insert when validation passed?** The column was `NOT NULL` with no default, and schema validation checks types, not nullability against the mapping.
