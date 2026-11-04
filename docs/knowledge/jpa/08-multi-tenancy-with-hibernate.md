# JPA 8. Multi-tenancy with Hibernate

> **Status: verified in the sources, not built yet.** §3.4 builds it (V9, `Project` as the first tenant-owned entity). Everything here was read in the Hibernate 6.6.53 and Spring sources for Phase 3 decision D4 (2026-10-13); update it with what §3.4 actually shows. The request side (the gate, roles) is in [security 07](../security/07-tenancy-and-org-authorization.md). Sources: [SOURCES.md](SOURCES.md).

## The problem it solves

TaskFlow keeps every customer's data in one schema (Phase 3 D2), so isolation is a property of **every query**. The membership gate checks the **request** ("may this caller act in organization 42?"), not the **query**. The day someone writes `projectRepository.findById(id)` with an id from the URL, the gate passed (you are a member of your own organization) and the row came back from someone else's. Phase 8's Specification queries, built at runtime, are where a forgotten `WHERE organization_id = ?` is most likely. The scope must be automatic.

## The options, and what each really does

| Option | What it does | Verdict |
|---|---|---|
| Explicit only | Every repository method takes `organizationId` and puts it in its `WHERE` | One forgotten condition is a leak that passes every single-tenant test |
| `@Filter` / `@FilterDef` | A named SQL condition added to queries when enabled on a session | **Ignores `findById`** unless `applyToLoadByKey = true` (still `@Incubating`); does nothing until enabled (or `autoEnabled`); doesn't set `organization_id` on insert |
| **`@TenantId`** (chosen, D4) | Hibernate's discriminator multi-tenancy | Covers `find()`, to-one fetches and inserts (below) |
| PostgreSQL row-level security | The database refuses other tenants' rows on every path | Strongest; optional §3.6 (costs below) |
| `@Where` | — | **Deprecated since 6.3** (→ `@SQLRestriction`): a fixed, always-on, unparameterised condition; it can't carry "the current tenant" |

## How `@TenantId` works (*verified*, Hibernate 6.6.53)

- **It's a filter** named `_tenantId`, condition `<column> = :tenantId` (`TenantIdBinder`), declared with **`applyToLoadByKey = true`**: unlike a plain filter it applies to `find()` by id and to fetching a to-one association.
- **It's switched on when the session opens,** with the value from your `CurrentTenantIdentifierResolver` (`AbstractSharedSessionContract.setUpMultitenancy`; `SessionFactoryImpl`'s builder calls `resolveCurrentTenantIdentifier()`), unless the resolver says the value is a **root** tenant (`isRoot`), which sees everything.
- **Every session needs a non-null tenant id** once any entity has `@TenantId`, or Hibernate throws "SessionFactory configured for multi-tenancy, but no tenant identifier specified" — including the sessions behind login, refresh, reset and the per-request session check. Non-tenant requests need a **"no tenant" value** that matches nothing. (This is why the §3.0 tests had to exist first: a resolver bug breaks exactly those flows.)
- **On insert,** a null tenant attribute is filled from the session; an explicitly set different value is **refused** (`PropertyValueException`) unless the session is root (`TenantIdGeneration`). The attribute is made non-updatable.
- **Filters apply to HQL/criteria `SELECT`s and to bulk HQL `UPDATE`/`DELETE`** (`BaseSqmToSqlAstConverter`; *read, not run*). They never reach **native SQL**.

## Timing: the tenant is fixed when the session opens

With `open-in-view=false`, a session lives for one transaction, so "when the session opens" means "when the `@Transactional` method starts" ([02](02-persistence-context-and-flushing.md)). The gate fills `TenantContext` in `preHandle`, before any service runs. A plain `@Filter` enabled in a web interceptor (the tutorial approach) would be enabled on a session the service never uses.

## The resolver and request scope

Hibernate calls the resolver with no arguments, so it can only read ambient state: TaskFlow's request-scoped `TenantContext` (D5, [spring 03](../spring/03-filters-interceptors-and-request-scope.md)). Outside a request (a `@Scheduled` job, a JPA slice test, an `@Async` thread), touching it throws `ScopeNotActiveException` (*verified* in `AbstractBeanFactory`; *seen* with `@Async`). So the resolver must **check for an active request first** and answer "no tenant". JPA slice tests set a tenant by activating a request (`RequestContextHolder.setRequestAttributes(...)`) or test through HTTP.

## The holes, stated (the compromise)

- **Native SQL and plain JDBC bypass it.**
- **References aren't checked:** `getReferenceById(otherTenantsId)` assigned to a relation goes through; composite foreign keys close it from Phase 4 ([06](06-schema-constraints-and-invariants.md)).
- **"Root" must never be reachable by accident.**
- **Access tables aren't tenant-filtered.** Memberships and invitations are read before the tenant is known (the gate's lookup) and across tenants ("my organizations", "my invitations"), so they never get `@TenantId`; a root session for them would put the bypass in the same phase as the wall. Every query on them names `organization_id` (from `TenantContext`) or `user_account_id` / the caller's email itself, and tests prove it (§3.1 mutations 3, 8, 11; §3.2 mutations 12, 13).

## Row-level security (optional §3.6), and why it isn't the default

RLS is enforced by PostgreSQL on **every** path: native SQL, JDBC, a reporting job, `psql` with the app's credentials. Its costs:
- The app must connect as a role that **doesn't own the tables and has no `BYPASSRLS`** (owners and superusers skip RLS): separate credentials for Flyway and the app everywhere.
- Each transaction must `SET LOCAL app.org_id = …` before its first query.
- Legitimate cross-tenant reads need their own policies.
- **Testcontainers connects as a superuser, which bypasses RLS: tests pass with RLS silently off** unless the test datasource uses the restricted role.

## Interview questions

1. **Why isn't a membership check in the controller enough?** It protects the request, not the query: another tenant's id still loads unless every query is scoped.
2. **`@Filter` or `@TenantId`?** A plain filter ignores primary-key lookups, must be enabled on the exact session, and doesn't fill the column on insert; `@TenantId` does all three.
3. **Where does `@TenantId` stop protecting you?** Native SQL, references set by id, anything run as root.
4. **Why can't the membership table use `@TenantId`?** The gate reads it to decide the tenant and "my organizations" reads across tenants: it's an access table, scoped explicitly.
