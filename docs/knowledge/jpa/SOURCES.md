# JPA pages — source map

> Every JPA / persistence lesson in Phases 0–3's documents, where it was written, and the page that now holds it. Built 2026-10-27; the phase documents were not changed (they remain the record; these pages are the topic view). Pages: [01](01-entities-and-mapping.md) entities · [02](02-persistence-context-and-flushing.md) persistence context · [03](03-transactions.md) transactions · [04](04-queries-projections-and-paging.md) queries · [05](05-bulk-updates-and-concurrency.md) concurrency · [06](06-schema-constraints-and-invariants.md) schema · [07](07-auditing-and-time.md) auditing & time · [08](08-multi-tenancy-with-hibernate.md) multi-tenancy · [09](09-testing-jpa.md) testing.

## Phase 0

| Source | Lesson | Now in |
|---|---|---|
| Log §2 | `insertable = false` on a `NOT NULL` column: first insert fails, `validate` passes | 01; 06 |
| Log §2 | `org.hibernate.sql` (lowercase) logs nothing | 04 |
| Log §2 | Check-then-insert race (30 POSTs → 4 × 500); constraint → 409; no constraint-name registry in `common` | 05; 06 |
| Log §2 | The race didn't reproduce at 6 requests, did at 30 | 05 |
| Log §2 | `?sort=` client-controlled 500 and joins → whitelist | 04 |
| Log §2 | Count query skipped on a short first page | 04 |
| Log §3 *Schema & database* | Flyway only · `validate` · `clean-disabled` · immutable migrations · named constraints · `timestamptz` · lengths + `CHECK` · sequence 50 = `allocationSize` | 06; 01 |
| Log §3 *JPA & transactions* | `open-in-view=false` · `@Transactional` in services, `readOnly` · DTO mapping inside the transaction · no `equals`/`hashCode` · no audit setters · `updatable = false` on created fields | 02; 03; 01; 07 |
| Log §4 §0.4 | Checksums, roll forward · Flyway before the EMF · two underscores · `varchar(n)` is a constraint | 06 |
| Log §4 §0.5 | `@MappedSuperclass` vs `@Embeddable` vs `@Inheritance` · `@EntityListeners` · `@EnableJpaAuditing` on its own config · `timestamptz` has no zone · nanos vs micros · runtime vs DDL-only annotations | 01; 07 |
| Log §4 §0.6 | Proxy self-invocation · `findAll(Pageable)`, `Page.map` · sort by field names · offset vs keyset · sequence gaps | 03; 04; 01 |
| Log §4 §0.7 | Hibernate vs Jakarta `ConstraintViolationException` | 02 |
| Log §5 Q2–11, 19, 20, 22, 24, 25 | Flyway, `validate`, open-in-view, `Instant`, `@Data`, `IDENTITY`, unique index, gaps, SQL logging, races, sort whitelist, keyset, three layers, tiebreaker | interview sections of 06, 02, 07, 01, 05, 04 |
| Log §7 | Field without migration · `insertable` · 30 POSTs → zero 500s | 06; 01; 05 |
| Log §8 | Public no-arg constructor on `Organization` (fixed §3.1) · `@Size(min = 3)` stricter than the `CHECK` | 01; 06 |
| Requirements decision 3 | Lombok banned on entities, with reasons | 01 |
| Requirements §0.5 | No `@Version` in `BaseEntity` | 05 |
| Requirements concepts / §0.10 | `open-in-view`; no H2 | 02; 09 |
| Requirements §0.6 | Measured baseline: 2 queries | 04 |
| `TESTING_GUIDE.md` §6.1–6.3, 6.5–6.7, §7 | Auditing import · no `replace = NONE` · flush + clear · integration data commits · timestamp precision · translation at the repository · context caching | 09; 02; 07 |

## Phase 1

| Source | Lesson | Now in |
|---|---|---|
| Log §3.4 | One conditional `UPDATE` makes a link single-use · revoke + partial unique index | 05; 06 |
| Log §3.5 | `save` ≠ flush ≠ commit; `saveAndFlush` | 02 |
| Log §3.5 | Rollback-only after a DB error; catch outside | 03 |
| Log §3.5 | Email after commit from another class; `MANDATORY` via `this` did nothing | 03 |
| Log §3.5 | Propagation; the counter rollback trap; records saved with what they record | 03 |
| Log §3.5 | Whole-row writes; `clearAutomatically`; `@DynamicUpdate` | 02 |
| Log §3.5 | `@Modifying` skips the persistence context and auditing | 02; 07 |
| Log §3.5 | `findIdByEmail` loads the entity | 04 |
| Log §3.5 *smaller* | DB defaults · `@Table(name)` · `validate` doesn't check PKs · migrations frozen | 01; 06 |
| Log §3.6 | Counter in SQL; lock statement's row count | 05 |
| Log §3.7 | `CHECK`s · named constraints, 63 chars · `varchar(200)` · `inet` · `@Immutable` + trigger · FK index + `ON DELETE` | 06; 01 |
| Log §3.9 | Enum stored as text, proved with native SQL | 01; 09 |
| Log §3.11 | Entity read `Instant.now()` · `CHECK` comparing two clocks · login-history sort bugs | 07; 06; 04 |
| Log §4 rows | `saveAndFlush` + translation · `@DynamicUpdate` · events with what they record · stable sort | 02; 03; 04 |
| Log §5 Q18, 33–37, 39 | DB default · email in tx · flush vs commit · rollback-only · propagation · the lock came back · editing a migration | 01; 03; 02; 06 |
| Log §7 *seen* | Counter in the login tx · `findIdByEmail` · SQL unlock / `clearAutomatically` / `@DynamicUpdate` · email in tx · `ORDINAL` | 03; 04; 02; 03; 01 |
| Log §7 *planned* | `save` vs `saveAndFlush` race · self-invocation · check-then-act link · catching inside · Java counter · `UPDATE` on `security_events` | 02; 03; 05; 03; 05; 06 |
| Log §8 *accepted* | `user_tokens` keeps revoked rows until Phase 9 | 06 |
| Requirements decision 16, §1.4 | `@ManyToOne` eager by default → `LAZY` | 01 |
| Requirements §1.5, §1.6 | `SecurityEvent` holds a `Long` · `@Version` on the counter rejected (and blind to bulk updates) · `FOR UPDATE` rejected | 01; 05 |
| Requirements §1.2 | Entity implementing `UserDetails` rejected | 01 |
| Implementation reference A8–A10, Part F | Database timing · propagation · after commit · persistence facts | 02; 03; 04; 01; 06 |

## Phase 2

| Source | Lesson | Now in |
|---|---|---|
| Log §3 *Rotation* | Conditional `UPDATE`, row count; READ COMMITTED re-check · scalar re-read after losing | 05; 02 |
| Log §3 *Rotation* | Return, don't throw · `setRollbackOnly()` (D13) · `MANDATORY` events | 03 |
| Log §3 *Revocation* | One conditional `UPDATE`, first reason kept · owner in the `WHERE` | 05 |
| Log §3 *Revocation* | Revocation in the password's transaction | 03 |
| Log §3 *Revocation* | `Assert.notNull` before `<>` · fixed `ORDER BY` + `Pageable` | 04 |
| Log §3 *Persistence* | `updatable = false` ignored by HQL · truncate to micros · `id <> NULL` · `Long` id · migrations frozen | 01; 05; 07; 04; 06 |
| Log §4, §5 Q8, 9, 17, 19, 21, 24, §7 | Insert-only, one clock reading, conditional consume, revocation in the transaction; the matching questions and deliberate failures | 05; 07; 03; 04 |
| Requirements §2.3, D11 | `FOR UPDATE` rejected · insert-only entities | 05; 01 |
| Implementation reference A6–A9, Part F | Decide with the database · insert-only · one clock · boundaries · HQL `updatable` · `setRollbackOnly` · `Pageable` sort on constructor queries | 05; 07; 03; 04 |
| `JWT_AND_SESSIONS_GUIDE.md` §9–10 (persistence traps) | Entity before losing a race · reading an entity after a bulk update · `@Transactional` via `this` · login not transactional | 02; 05; 03 |

## Phase 3

| Source | Lesson | Now in |
|---|---|---|
| Log §3 *What a failed login must leave behind* | Transactional login loses history, not the counter | 03; 09 |
| Log §3 *Testing time / races* | Ticking clock; races with a latch, counts | 07; 05; 09 |
| Log §3 *Invariants the database enforces* | Deferred trigger · fails at commit · name lost at commit · rolled-back tests skip it · cleanup order | 06; 03; 09 |
| Log §3 *Sorting a joined query* | Root alias prefixing, join variables, translation | 04 |
| Log §3 *Changing shared rows safely* | Expected value in the `WHERE` · row locks · audit columns · read back · no-op | 05; 07; 02 |
| Log §3 *Testing what only a race would show* | Stale-value tests · plain-member outsider · widened window · 409 or 403 | 05; 09 |
| Log §5 Q10, 11, 13, 14, 16, 17 | The matching interview answers | 06; 09; 05; 07 |
| Requirements D4 (*verified*), D5, D8 | `@TenantId` mechanics, holes, RLS costs, request scope, access tables | 08 |
| Requirements §3.1 | V7 design, error worded like PostgreSQL's, no deletes in migrations, `nextval` safety | 06; 01 |
| Requirements §3.2 + review | Bulk bypass · flush order · `@Version` vs expected role · promote-first · step-2 count · `String` vs enum parameter · stale membership entity | 02; 05; 04 |
| Requirements §3.3 (brief, 2026-10-20) | `:now` from the `Clock`, never SQL `now()` · concurrent re-invites vs the snapshot · partial index can't hold `now()` · V8 `CHECK`s · accept as a conditional `UPDATE` | 04; 07; 05; 06 |
| Implementation reference A6–A8, A10, Part F | DB-enforced invariants · access tables · insert-only · read-decide-write · `doCommit` cause · extractor · sort prefixing · `JdbcTemplate` in slices · enum-typed parameters | 06; 08; 05; 03; 04; 09 |
