# JPA 4. Queries, projections, paging and sorting

> How TaskFlow asks the database questions: derived queries vs `@Query`, projections instead of entities, and the pagination and sorting rules every list follows. Sources: [SOURCES.md](SOURCES.md).

## A method name can't choose the column a query returns

In Spring Data's naming rules, the words between `find` and `By` are ignored.

*What happened to us (Phase 1, tested on a copy):* `Optional<Long> findIdByEmail(String email)` loaded the whole `UserAccount`, then failed with `JpaSystemException` turning an account into a `Long`. It failed only for emails that **exist**, so a quick test with an unknown email looked fine; and the lockout listener that used it swallowed the error, so lockout was silently off ([security 05](../security/05-account-protection-and-privacy.md)).

**Rule:** to select a column, write the query (`@Query("select u.id from UserAccount u where u.email = :email")`). Derived queries are for whole entities or `exists`/`count`.

## Select what you need: scalars and projections

Hot paths never load an entity they don't need:
- **A boolean:** the per-request session check, `select case when count(s) > 0 then true else false end … where s.id = :sessionId and s.userAccountId = :userAccountId and …` (`UserSessionRepository.isLive`).
- **A scalar:** the tenant gate's role lookup, `select m.role from OrganizationMembership m where …` (`findRole`), on every org-scoped request.
- **A constructor projection:** `select new …MemberResponseDto(u.id, u.username, u.displayName, m.role, m.createdAt) from …`.

Gains: less data (no password hash loaded to show a username), no entity a later bulk statement could leave stale ([02](02-persistence-context-and-flushing.md)), and a query you can read and index for.

## Joining features without mapping them

HQL joins two entities by any condition, not only a mapped association: `from OrganizationMembership m join UserAccount u on u.id = m.userAccountId`. The `organization` feature reads usernames without mapping `UserAccount` ([01](01-entities-and-mapping.md)).

## Parameters and literals

- **A parameter's type must match the attribute it's compared with.** Hibernate 6.6.53 coerces through the parameter's `JavaType`; `EnumJavaType` doesn't override `coerce`, so a `String` (`"ADMIN"`) bound where an `OrganizationRole` attribute is compared fails with `QueryArgumentException` (*verified in the source*; predicted in the §3.2 review). **Rule:** convert once (`OrganizationRole.valueOf` after validation) and type repository parameters as the enum.
- **Enum constants can be written in JPQL by fully qualified name:** `m.role <> com.abhinav.taskflow.common.tenant.OrganizationRole.OWNER` (§3.2's `leave`, `demoteOwner`, `promoteToOwner`; exercised by the integration tests).
- **`<> :param` with a `NULL` parameter matches nothing.** `id <> NULL` is *unknown*, not true. *What happened to us (Phase 2, caught in review):* `revokeAllExcept(account, null, …)` would have revoked **nothing**, silently. **Rule:** `Assert.notNull` before any `<>` on a parameter. *Seen (§2.4 deliberate failure 4):* a null session id → 500 and the whole change rolled back, instead of "changed, nothing revoked".
- **"Now" is a parameter from the injected `Clock`, never SQL `now()` / `current_timestamp`.** SQL's clock ignores the application's: the test clock can't move it, so "expired invitations can't be accepted" becomes untestable without waiting 7 days (§3.3 brief, 2026-10-20). Every Phase 2 query already passes `:now`. ([07](07-auditing-and-time.md))

## Pagination

- **`JpaRepository.findAll(Pageable)` and `Page<T>`.** `Page.map()` converts the content and keeps the metadata, no extra query.
- **Two queries per page:** the rows (`limit ? offset ?`) and a `count`. *Measured (Phase 0, 2026-09-22):* exactly **2 queries** for `GET /api/v1/organizations`, the baseline for Phase 8's N+1 work.
- **Spring Data skips the count** on a first page holding fewer rows than the page size: measuring with 1 row and `size=20` shows one query and looks broken. Measure with more rows than the page size.
- **Joined queries get an explicit `countQuery`** (a count without the join, same `WHERE`).
- **Offset pagination degrades with depth:** `OFFSET 100000` makes PostgreSQL produce and discard 100,000 rows; keyset (seek) pagination is the answer at scale.
- **`Pageable` is built by hand** in `PageableFactory` from `ApiProperties` (Phase 0 decision 12): one place clamps the size and adds the tiebreaker.

## Sorting

- **Sort properties are entity field names, not column names.**
- **Whitelist per endpoint.** *What happened to us (Phase 0):* `?sort=doesNotExist` → `PropertyReferenceException` → a client-controlled 500; and every field was sortable, so in Phase 5 `?sort=parent.title` would let a client force a join. Anything off the list is 400 `INVALID_SORT_PROPERTY`; the list also stops a client sorting (and so probing) by a column it can't see (`?sort=email` on members).
- **Always a deterministic tiebreaker** (`id`): rows sharing a sort value have no defined order, so pages repeat or skip rows.
- **A default sort must exist on the entity it's applied to.** *What happened to us (Phase 1, caught in review):* the login-history query would have inherited `PageableFactory`'s default `createdAt`, a field `SecurityEvent` doesn't have (a 500); it also took one event type instead of three and hard-coded its order in the method name, and an extra factory method skipped the tiebreaker. Each list endpoint passes its own default (`SessionService.LIST_SORT`, `MembershipService.LIST_SORT`) through the one factory.
- **`PageableFactory` validates whatever sort it receives, including the default:** a default naming `id` needs `id` in the whitelist (Phase 3 "my organizations").
- **One place for the order.** A fixed `ORDER BY` plus the `Pageable`'s sort sorts twice; the order lives in the `Pageable`. Spring Data appends a `Pageable`'s sort to a constructor-expression query (*seen in a scratch run*, Phase 2).

### How Spring Data qualifies a sort property

Spring Data prefixes each sort property with the query's **first** alias unless it already starts with that alias, a join variable or a select alias (*verified*: Spring Data JPA 3.5.13, `JpaQueryTransformerSupport.shouldPrefixWithAlias`; join variables registered by `HqlSortedQueryTransformer.visitJoinPath`). In Phase 3:
- "My organizations" is written `from Organization o join OrganizationMembership m …`, so `name` and `createdAt` become `o.name`, `o.createdAt`.
- The members list starts `from OrganizationMembership m join UserAccount u …`; the API's `username` would become `m.username`, so `MembershipService` translates once: `username` → `u.username`, `joinedAt` → `createdAt` (→ `m.createdAt`).

## Seeing the SQL

- `org.hibernate.SQL` at DEBUG for statements, `org.hibernate.orm.jdbc.bind` at TRACE for bound values. Not `show-sql`, which prints to stdout and bypasses logging.
- **Logger names are case-sensitive.** *What happened to us (Phase 0):* `org.hibernate.sql` created a logger nobody writes to.
- **No Hibernate SQL logging in prod:** bound parameters carry personal data and secrets. A slow query in production is found with database-side tools, not DEBUG logging.

## Interview questions

1. **Why did `findIdByEmail` return an entity?** Spring Data ignores the words between `find` and `By`; one column needs `@Query`.
2. **Why project into a DTO?** Less data, no stale entities after bulk updates, and the query says exactly what the endpoint needs.
3. **How do you stop a client controlling your query plan?** Whitelist sortable fields per endpoint.
4. **What causes unstable pagination?** No unique tiebreaker in the sort.
5. **How would you paginate a million rows?** Keyset pagination.
6. **Why would `WHERE id <> :except` change nothing?** A null `:except` makes the comparison unknown for every row.
7. **Why pass `:now` instead of using SQL `now()`?** One clock for the whole application, and tests can move it.
