# JPA 7. Auditing and time

> Who changed a row and when: JPA auditing, where it silently stops working, and how TaskFlow keeps what's in memory equal to what's stored. Sources: [SOURCES.md](SOURCES.md).

## How JPA auditing is wired

- `BaseEntity` declares `@CreatedDate`, `@CreatedBy`, `@LastModifiedDate`, `@LastModifiedBy` and **`@EntityListeners(AuditingEntityListener.class)`**; without the listener the annotations are inert.
- `@EnableJpaAuditing` sits on its own `@Configuration` (`AuditingConfig`), **not the main class**: there it would switch on in web slices that have no JPA and fail them.
- `AuditorAware` (`AuditAwareImpl`) supplies the "by" value from the security context.
- Audit fields have no public setters, and `created_at`/`created_by` are `updatable = false`.

## `created_by` holds the username

Phase 0 decision 9: the **username**, never the email. An email can change, a username is the stable handle, and it fits `varchar(50)`. V7's backfill relied on it (`join user_accounts u on u.username = o.created_by`).

*What happened to us (Phase 1, deliberate failure):* the auditor used `authentication.getName()`, and `created_by` became `alice@example.com`. `getName()` returns `UserDetails.getUsername()`, which is whatever the user signs in with: the email. The auditor reads the app username from **our** principal type.

## The auditor follows the principal's type, silently

When the principal type changed in Phase 2 (`TaskflowPrincipal` → `AuthenticatedUser`), everything reading it had to follow. `@AuthenticationPrincipal(errorOnInvalidType = true)` fails loudly (500); the auditor fails **silently** (`system`). *Seen (Phase 2 §2.2 deliberate failure 5):* the auditor on the old type → `created_by = system`. Its unit test now pins the type both ways, and an integration test asserts `createdBy` is the caller's username.

## Where auditing doesn't happen

- **Bulk statements.** A `@Modifying` `UPDATE` never passes through the entity listener; statements that change memberships set `updated_at`/`updated_by` from the `Clock` and the caller's username. *Seen (Phase 3 §3.2 deliberate failure 5):* without it, `updated_by` still said who inserted the row. Phase 1's lockout counter is an accepted exception (`updated_at` doesn't move when an account locks).
- **`@DataJpaTest` doesn't load your auditing config:** without `@Import(AuditingConfig.class)`, the first insert fails with `null value in column "created_at"` ([09](09-testing-jpa.md)).
- **Rows without meaningful updates have no `updated_*` columns:** append-only events and insert-only sessions extend `IdentifiedEntity` ([01](01-entities-and-mapping.md)).
- **A no-op isn't a change:** setting a member to the role they already hold returns 200 and writes nothing, so `updated_by` never claims a change nobody made (Phase 3 §3.2).

## `Instant` and `timestamptz`

- **`timestamptz` doesn't store a zone:** it stores an absolute UTC instant and discards the offset. `Instant` means exactly that; `LocalDateTime` has no zone; `OffsetDateTime` promises an offset the column can't keep.
- **Precision:** `Instant` holds nanoseconds, `timestamptz` microseconds; a value read back can differ by a few hundred nanoseconds.
  - Tests compare with `isCloseTo(expected, within(1, ChronoUnit.MILLIS))`.
  - Code **truncates to microseconds in the factory** (`now.truncatedTo(ChronoUnit.MICROS)` in `UserSession.start`, Phase 2), so in-memory values equal the stored row. JWT times are whole seconds: `iat` is truncated to seconds before `exp` is computed ([security 04](../security/04-jwt-refresh-and-sessions.md)).

## Time is injected, read once, and passed down

- **An injected `Clock` bean**, never `Instant.now()` in business code. *What happened to us (Phase 1, caught in review):* an entity read the system clock itself.
- **Not SQL `now()` either:** "now" reaches queries as a `:now` parameter from the same `Clock` (Phase 2 queries; §3.3 brief: otherwise the test clock can't move it and expiry is untestable).
- **One reading per operation** (Phase 2 rule A8): a login's session timestamps, refresh token timestamps and access token `iat` are the same instant.
- **One clock in a comparison.** *What happened to us (Phase 1):* a `CHECK` compared a database time with a Java time.
- **Tests move time without sleeping:** a ticking `AdjustableClock` (real time + offset), `@Primary` in the shared test configuration (Phase 3 §3.0). It also moves the JWT decoder's expiry check: advancing past 15 minutes (or §3.3's 7 days) expires every access token obtained before, so log in again after `advance`.

## Interview questions

1. **`Instant` or `LocalDateTime` for a timestamp column?** `Instant`: `timestamptz` is an absolute instant; `LocalDateTime` has no zone and `OffsetDateTime` claims an offset the column discards.
2. **Why doesn't auditing fill `updated_by` on your role changes?** Auditing runs on entity flushes; a bulk JPQL update goes straight to SQL, so it sets the columns itself.
3. **Why is `created_by` a username?** It survives an email change; and the auditor reads it from the app's principal because Spring's `getName()` returns the login email.
4. **Why did an exact timestamp assertion fail after a round trip?** Nanoseconds in Java, microseconds in PostgreSQL.
