# JPA 9. Testing persistence code

> What a JPA test proves, and the ways it silently proves nothing. General test strategy is in [testing 01](../testing/01-test-strategy-and-slices.md) and [testing 02](../testing/02-security-race-and-time-tests.md); this page holds the persistence-specific lessons. Sources: [SOURCES.md](SOURCES.md).

## Real PostgreSQL, never H2

Tests run against PostgreSQL 17 in Docker (Testcontainers, `@ServiceConnection`). H2 gets three things wrong in ways that make tests pass and production fail: `SELECT … FOR UPDATE` / locking semantics (Phase 5), PostgreSQL's constraint error codes (the 409 mapping), and real index behaviour including partial indexes (Phase 8). Phase 3 adds deferred constraint triggers and `inet`.

## `@DataJpaTest`: what it loads, what it doesn't

- **It rolls back every test:** fast and isolated, but anything checked at commit never runs (below).
- **It doesn't load your `@Configuration` classes:** `@Import(AuditingConfig.class)` or the first insert fails with `null value in column "created_at"`. The error points at the database; the cause is a missing import.
- **`@AutoConfigureTestDatabase(replace = NONE)` isn't needed** since Boot 3.4: a `@ServiceConnection` datasource isn't replaced (*verified* on 3.5.16; older guides say otherwise).
- **It includes `JdbcTemplateAutoConfiguration`, and its `JdbcTemplate` joins the test's transaction** (*verified*, Phase 3): that's how a slice runs `SET CONSTRAINTS ALL IMMEDIATE` or reads raw columns.
- **Same annotations, same context:** every JPA slice uses exactly `@DataJpaTest @Import({TestcontainersConfiguration.class, AuditingConfig.class})`. One more import is a new context and a new container; Phase 3 tested `findRole` through the repository rather than importing `JpaMembershipLookup` for this reason.

## Without flush and clear, you're not testing the database

- **Reading back:** `save()` → `findById` returns the same Java object from the persistence context, no SQL. Flush, `clear()`, then read; or `TestEntityManager.persistFlushFind()`.
- **Testing a constraint:** two `save()`s with the same slug and no flush throw **nothing** (*verified*): the `INSERT` never reached PostgreSQL. Use `saveAndFlush`.
- **The exception type depends on the path:** `TestEntityManager.persistAndFlush` → Hibernate's `ConstraintViolationException`; `repository.saveAndFlush` → Spring's `DataIntegrityViolationException` ([02](02-persistence-context-and-flushing.md)). Assert the type production code sees.

## Deferred constraints in rolled-back tests

A test that rolls back never commits, so a **deferred** check never runs: `OrganizationRepositoryTest` persists an ownerless organization and passes, with a comment saying why. `OrganizationMembershipRepositoryTest` proves the owner invariant by flushing, then `SET CONSTRAINTS ALL IMMEDIATE`, which raises `ck_organizations_has_owner` on the spot.

## Integration tests commit

`@SpringBootTest(webEnvironment = RANDOM_PORT)` handles each request on a server thread in its own transaction, which **commits**. Data stays for the next test and the next class (one shared container).
- **Clean up in `@BeforeEach`,** or use unique values per test.
- **Cleanup order follows the constraints:** since V7, organizations before users (the owner trigger refuses to delete an owner). `TestUsers.deleteAll()` does both, in that order, in one place, because an organization left by **another** class would otherwise break this class's cleanup.
- **Data with no API yet is inserted with SQL** (memberships before invitations exist, a locked account, an admin), ids from `nextval('global_id_seq')`.

## Assert the database, not just the status

- **The rollback trap only shows in the database.** *Seen (§3.0, mutation 8):* a transactional `login` still answered 401 and still counted the failure, but recorded **0** `LOGIN_FAILED` events instead of 4. The test asserts the rows.
- **"Nothing changed" is checked by reading the row:** after a wrong-password transfer, roles unchanged and `failed_login_attempts` = 1; after a same-role change, `updated_by` is still the inserting value.
- **Prove an enum is stored as text** with a native query.

## Timestamps

Nanoseconds vs microseconds: compare `within(1, ChronoUnit.MILLIS)` or truncate both. Time-dependent rules use the shared `AdjustableClock` (`advance`; `reset` in `@BeforeEach`), never `sleep`; queries must take `:now` from the `Clock` or the test clock can't reach them ([07](07-auditing-and-time.md)).

## Conditional statements and races

Repository tests call each conditional statement with stale and foreign values and expect 0 rows; the data must include the row only the tested condition excludes; race tests release N requests from one latch and assert counts ([05](05-bulk-updates-and-concurrency.md#proving-it)).

## Interview questions

1. **Why not H2?** It differs from PostgreSQL on locking, error codes and indexes.
2. **"Expected an exception, got none" on a constraint test?** The insert was never flushed.
3. **How do you test a deferred constraint when tests roll back?** Flush, then `SET CONSTRAINTS ALL IMMEDIATE`.
4. **Why clean up before each integration test instead of rolling back?** The request runs on a server thread in its own transaction and commits.
