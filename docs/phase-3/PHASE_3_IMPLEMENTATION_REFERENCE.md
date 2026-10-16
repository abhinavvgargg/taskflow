# Phase 3 — Implementation Reference (organizations & multi-tenancy)

> **What this is:** a reusable record of *what* Phase 3 built, *how* it's built, and the *rules* it follows, written so the same design can be rebuilt in another Spring Boot project without re-deriving it. Read Part D first if you're implementing it somewhere new.
> **What it isn't:** the decisions with their alternatives (`PHASE_3_REQUIREMENTS.md`), or what was learned and measured (`PHASE_3_LEARNING_LOG.md`).
> **Kept current:** updated at the end of **every Phase 3 sub-section**. The coverage table and the changelog say what's in.

| Sub-section | Feature | Status in this document |
|---|---|---|
| §3.0 | Tier-1 test debt and the test tools | ✅ tools covered in Part E (committed `ec5acad`) |
| §3.1 | Memberships, the owner invariant, the tenant gate, my organizations, members | ✅ covered (built 2026-10-15; `OPTIONS` fix 2026-10-16; your Postman run passed 2026-10-16; **not yet committed**) |
| §3.2 | Roles, removal, leaving, ownership transfer | ⏳ |
| §3.3 | Invitations | ⏳ |
| §3.4 | Projects and Hibernate's `@TenantId` | ⏳ |
| §3.5 | Profile, README | ⏳ |

**Stack:** Java 21 · Spring Boot 3.5.16 · Spring Framework 6.2.19 · Spring Security 6.5.11 · Spring Data JPA 3.5.13 · Hibernate 6.6.53 · PostgreSQL 17 · Flyway.

---

## Part A — Design principles and rules

Each rule says **what** and **why**: a rule without its reason gets "simplified" away.

### A1. Judge everything by what a member of A gets when they send B's ids

The membership check protects the *request*; it doesn't protect the *query*. Every org-scoped id (organization, member, invitation, project) is answered as if it didn't exist when it isn't the caller's: the same 404, byte for byte, bar timestamp, correlation id and path.

### A2. Two halves of isolation: the gate and the scope

- **The gate** (built in §3.1): may this caller act in organization O at all? One indexed lookup per request.
- **The scope** (§3.4): does every query in this request see only O's rows? Hibernate's `@TenantId` on tenant-owned entities.

The gate without the scope leaks through ids from URLs; the scope without the gate lets anyone pick any organization.

### A3. The organization is named in the path, and the membership is read on every request

`/api/v1/organizations/{orgId}/…`, with the role read from the database each time (D3). A removed or demoted member is refused on their **next** request, with the token they already hold. No org claims in the JWT.

### A4. One source for "the current organization"

The gate fills a request-scoped `TenantContext`. Services read the organization id and role **only** from it, never from a path variable or a body, so the value a query uses is always the value the gate checked. Org-scoped controllers don't even bind `{orgId}`.

### A5. Fail closed, and loudly

- A controller under the gated paths without an `{orgId}` variable is a programming error: **500**, never "skip the check".
- A principal that isn't `AuthenticatedUser` reaching the gate: **500**.
- Reading `TenantContext` before the gate entered a tenant, or outside a request: an exception (500), never a default.

### A6. The database enforces the invariants the code could forget

"Exactly one owner per organization": at most one by a **partial unique index** (immediate), at least one by a **deferred constraint trigger** (at commit). It holds for every code path, including ones not written yet (an admin tool, account deletion).

### A7. Access tables vs tenant-owned data

Memberships (and, in §3.3, invitations) are **access tables**: read before a tenant is known (the gate) and across tenants ("my organizations"). They never get `@TenantId`; **every query on them names `organization_id` or `user_account_id` in its `WHERE`**. Tenant-owned data (projects from §3.4) gets `@TenantId`.

### A8. Insert-only membership rows

`OrganizationMembership` has no setters and every column is `updatable = false`; role changes (§3.2) are conditional `UPDATE`s carrying the expected role in their `WHERE`. Phase 2's D11, for the same reason: a stale entity flush can't write an old role back.

### A9. `common` never imports a feature

The gate lives in `common/tenant` and asks an interface, `MembershipLookup`, which `organization` implements (exactly like Phase 2's `SessionStatus`).

---

## Part B — The architecture at a glance

### B1. Where each piece lives

```
common/tenant                                organization
─────────────                                ────────────
OrganizationRole (isAbove, isAtLeast)        Organization (factory create)
TenantContext (@RequestScope)                OrganizationMembership (insert-only)
TenantErrorCode                              OrganizationRepository (findMine)
MembershipLookup (interface) ◄────────────── JpaMembershipLookup
TenantGateInterceptor                        OrganizationMembershipRepository (findRole, findMembers)
TenantWebConfig (patterns)                   OrganizationService (create, listMine, get)
                                             MembershipService (listMembers)
common/error                                 OrganizationController, MemberController
CommonErrorUtility (names commit-time        OrganizationResponseDto (+ role), MemberResponseDto
  violations too)
```

### B2. An org-scoped request, end to end

```
security chain: bearer token → AuthenticatedUser (Phase 2)
DispatcherServlet: handler matched → path variables stored on the request (handleMatch)
TenantGateInterceptor.preHandle      (registered for /api/v1/organizations/* and /*/**)
  handler isn't a @Controller method → pass (Spring's OPTIONS answer, the resource handler's 404)
  orgId = path variable "orgId"      missing → 500 · non-numeric → 404
  role  = MembershipLookup.roleOf(orgId, caller.id)     one query, own read-only transaction
  none  → 404 ORGANIZATION_NOT_FOUND ("Organization not found", no id in the body)
  else  → TenantContext.enter(orgId, role)
controller → service: tenantContext.organizationId(), tenantContext.role(), requireAtLeast(...)
```

### B3. The schema (V7)

| Object | What | Why |
|---|---|---|
| `organization_memberships` | `organization_id`, `user_account_id`, `role` (`OWNER`/`ADMIN`/`MEMBER`), `BaseEntity` audit columns | One row per organization × user |
| `uk_organization_memberships_org_user` | unique `(organization_id, user_account_id)` | One membership per pair; serves the gate's lookup; the target of Phase 4's composite FKs |
| `ix_organization_memberships_user` | index `(user_account_id)` | "My organizations" |
| `uk_organization_memberships_one_owner` | partial unique `(organization_id) where role = 'OWNER'` | At most one owner, checked immediately: a transfer must demote before it promotes |
| `organization_has_owner_check` + `trg_organizations_has_owner` (after insert on organizations) + `trg_organization_memberships_has_owner` (after update/delete on memberships), both `deferrable initially deferred` | At least one owner, checked at commit | A cross-row rule a `CHECK` can't express; deferred so a transfer can pass through zero owners |
| FKs `ON DELETE CASCADE` to organizations and user_accounts | | Deleting an organization removes its memberships; deleting an **owner's** account is refused by the trigger at commit |
| Backfill | creator (`created_by` = username) becomes `OWNER` | Organizations created as `system` stay ownerless and unreachable |

The trigger raises `'organization % violates check constraint "ck_organizations_has_owner"'` with `errcode = check_violation`: worded like PostgreSQL's own message so Hibernate's PostgreSQL extractor reads the name (SQLState 23514 → message template).

---

## Part C — Each feature: what, how, rules, pitfalls

### C1. Memberships and the owner invariant (§3.1)

**What it does.** Records who belongs to which organization with which role, and guarantees in the database that every organization has exactly one owner.

**How it's built.**
- V7 (Part B3).
- `OrganizationMembership extends BaseEntity`: `Long organizationId`, `Long userAccountId` (ids, not associations: every operation works by id), `@Enumerated(STRING) OrganizationRole role`; all columns `updatable = false`; factory `of(organizationId, userAccountId, role)` with `Assert.notNull`s; `toString` without anything sensitive.
- `Organization`: factory `create(name, slug)`, protected no-arg constructor, no setters.
- `OrganizationRole`: declared `OWNER, ADMIN, MEMBER` (highest first); `isAbove` = strictly higher (`ordinal() < other.ordinal()`), `isAtLeast` = same or higher.

**Rules.**
- Creation inserts the organization and the `OWNER` membership in **one transaction** (`OrganizationService.create`); the deferred trigger refuses a commit without the owner.
- Membership changes are conditional statements (§3.2), never entity updates.

**Pitfalls.**
- **A deferred check never runs in a rolled-back test.** `@DataJpaTest` rolls back: flush, then `SET CONSTRAINTS ALL IMMEDIATE` to prove the invariant (an `OrganizationRepositoryTest` that persists an ownerless organization passes only because of the rollback).
- **A commit-time violation arrives without Hibernate's `ConstraintViolationException`** (Hibernate's "Unable to commit" around the raw `PSQLException`, translated by Spring by SQLState). `CommonErrorUtility.constraintNameOf` therefore also reads the name from a raw `SQLException` with `PostgreSQLDialect`'s extractor; otherwise the 409 carries `constraint: null`.
- **The role rule is the declaration order.** Insert a future role in the wrong place and who-outranks-whom changes silently; extend `OrganizationRoleTest`'s 9-pair table with any new role.
- **Shared test databases:** an owner's account can't be deleted while it owns an organization. Test cleanup deletes organizations first (`TestUsers.deleteAll`).

**Files.** `db/migration/V7__create_organization_memberships.sql`; `organization/OrganizationMembership`, `Organization`, `OrganizationMembershipRepository`; `common/tenant/OrganizationRole`; `common/error/CommonErrorUtility`.

---

### C2. The tenant gate (§3.1)

**What it does.** Before any org-scoped controller runs, checks that the caller is a member of the organization in the URL; fills `TenantContext` or answers 404.

**How it's built.**
- `TenantGateInterceptor implements HandlerInterceptor` (a `@Component`), registered by `TenantWebConfig` (`WebMvcConfigurer`) for `/api/v1/organizations/*` and `/api/v1/organizations/*/**`, **not** the collection `/api/v1/organizations`.
- `preHandle`:
  1. **Only our controller methods are gated:** `handler instanceof HandlerMethod hm && AnnotatedElementUtils.hasAnnotation(hm.getBeanType(), Controller.class)`. Everything else passes (it runs none of our code).
  2. `orgId` from `request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)`; missing → `IllegalStateException` (500).
  3. `Long.parseLong`; failure → the 404.
  4. Caller id from `SecurityContextHolder`'s `AuthenticatedUser`; any other principal → `IllegalStateException`.
  5. `membershipLookup.roleOf(orgId, callerId)`; empty → `ResourceNotFoundException(ORGANIZATION_NOT_FOUND, "Organization not found")`.
  6. `tenantContext.enter(orgId, role)`.
- `JpaMembershipLookup.roleOf`: `@Transactional(readOnly = true)`, one JPQL query selecting `m.role` (no entity) by `(organizationId, userAccountId)`.

**Rules.**
- **An interceptor, not a filter:** it runs after the route is matched (path variables parsed) and its exceptions go through `@RestControllerAdvice` (`applyPreHandle` sits inside `doDispatch`'s `try`: *verified* in 6.2.19), so a refusal is the standard `ProblemDetail`.
- **Fail closed on a missing `orgId`.** A gate that skips when its input is missing is a gate with a door in it.
- **Non-member = non-existent = non-numeric:** the same 404 with a **constant** detail and no id property (the id would make two misses differ).
- **Gate `@Controller` methods, all of them, and nothing else.** That predicate is exactly the one Spring uses to register controllers (`RequestMappingHandlerMapping.isHandler`), and `HandlerMethod` stores the user class (`ClassUtils.getUserClass`), so CGLIB-proxied controllers (Phase 4's method security) stay gated (*both verified* in 6.2.19).

**Pitfalls.**
- **`OPTIONS` without a mapping** is answered by Spring's private `HttpOptionsHandler`, wrapped in a `HandlerMethod` built in `handleNoMatch`, which never stores path variables. Gating every `HandlerMethod` turned every `OPTIONS` on an org path into a 500 with an ERROR stack trace, at any signed-in user's will (*found in review, fixed 2026-10-16*). "Skip every `OPTIONS`" was rejected: an `OPTIONS` mapping we write would then be ungated. *A test pinning that is still owed.*
- **Patterns:** `/api/v1/organizations/**` includes the collection, which has no `{orgId}` → create and "my organizations" become 500 (deliberate failure 2).
- **Web slices load the gate** (`@WebMvcTest` includes `HandlerInterceptor` and `WebMvcConfigurer` beans: *verified* in the 3.5.16 bytecode) but not `TenantContext` or `JpaMembershipLookup`: every slice needs `@MockitoBean MembershipLookup` and `@Import(TenantContext.class)`, and org-path slice tests authenticate with `authentication(new TaskflowAuthenticationToken(...))` (`@WithMockUser`'s principal is a 500 at the gate, by design).
- A path under the patterns that no controller maps ends in Spring's resource handler → its normal 404 (not gated).

**Files.** `common/tenant/TenantGateInterceptor`, `TenantWebConfig`, `MembershipLookup`, `TenantErrorCode`; `organization/JpaMembershipLookup`.

---

### C3. The request-scoped tenant context (§3.1)

**What it does.** Holds "the organization this request acts in, and the caller's role there" for exactly one HTTP request.

**How it's built.** `@Component @RequestScope class TenantContext` (not final, no-arg constructor: the scoped proxy is a CGLIB subclass): `enter(long, OrganizationRole)` once (a second call throws, a null role throws); `isEntered()`; `organizationId()` and `role()` throw `IllegalStateException` before `enter`; `requireAtLeast(required)` throws `RequestForbiddenException(ORGANIZATION_ROLE_REQUIRED)` with `requiredRole`.

**Rules.**
- Request scope, not a `ThreadLocal`: Spring discards it with the request, so nothing is left on a pooled Tomcat thread (D5).
- Outside a request it throws `ScopeNotActiveException` (*seen*: an `@Async` method reading it → 500). Phase 9's background work gets the organization id explicitly.
- A 403 is only ever raised **inside** an organization the caller belongs to (they already know it exists).

**Pitfalls.** A unit test can `new TenantContext()` directly; a JPA slice that needs it must activate a request (`RequestContextHolder.setRequestAttributes(...)`), needed from §3.4.

**Files.** `common/tenant/TenantContext`.

---

### C4. My organizations, one organization, its members (§3.1)

**What it does.** `GET /organizations` lists the caller's organizations with their role; `POST /organizations` creates one with the caller as owner; `GET /organizations/{orgId}` and `/members` are behind the gate.

**How it's built.**
- `OrganizationRepository.findMine(userAccountId, pageable)`: JPQL constructor expression **rooted on `Organization o`** joined to `OrganizationMembership m` where `m.userAccountId = :userAccountId`; separate count query. Rooted on `o` so the Pageable's sort names (`name`, `createdAt`, `id`) resolve against it.
- `OrganizationMembershipRepository.findMembers(organizationId, pageable)`: `MemberResponseDto(u.id, u.username, u.displayName, m.role, m.createdAt)` from `m join UserAccount u on u.id = m.userAccountId`.
- `MembershipService.toQueryPaths`: API sort names → query paths (`username` → `u.username`, `joinedAt` → `createdAt`), applied **after** `PageableFactory` validated the API names and appended the `id` tiebreaker.
- `OrganizationService.get()`: `findById(tenantContext.organizationId())`, role from the context; a miss (deleted in between) → the same 404.
- Defaults: "mine" `name` asc, `id` asc (allowed: `name`, `createdAt`, `id`); members `username` asc (allowed: `username`, `joinedAt`).

**Rules.**
- "Mine" puts the caller in the `WHERE`; members put the organization (from the context) in the `WHERE` (A7).
- Members are listed **without email** (data minimisation).
- Unknown sort names are a 400 `INVALID_SORT_PROPERTY` (the allow-list also stops probing hidden columns by sort order).

**Pitfalls.**
- **Spring Data prefixes sort properties with the root alias unless they start with a join variable** (*read in* spring-data-jpa 3.5.13): `username` would become `m.username`. Translate API names in one place.
- A concurrent duplicate slug still surfaces as the generic 409 `RESOURCE_CONFLICT` (`uk_organizations_slug`), not `DUPLICATE_SLUG` (nit; registration's `saveAndFlush` + catch pattern would map it).

**Files.** `organization/OrganizationRepository`, `OrganizationMembershipRepository`, `OrganizationService`, `MembershipService`, `OrganizationController`, `MemberController`, `OrganizationResponseDto`, `MemberResponseDto`, `OrganizationErrorCode` (`DUPLICATE_SLUG` only).

---

## Part D — Rebuilding this in another project: the recipe

**Prerequisites:** bearer authentication with a small principal type (Phase 2 equivalent), a `ProblemDetail` error handler with codes, a `PageableFactory`-style sort allow-list, Flyway, Testcontainers.

1. **Schema:** a memberships table (organization, user, role) with unique `(organization, user)`, an index on user, a partial unique index for one owner, and a deferred constraint trigger for "has an owner" (on organization insert and membership update/delete). Word the trigger's message like PostgreSQL's check-violation message. Backfill owners for existing organizations. Verify in a scratch database.
2. **Role enum** highest-first with `isAbove` / `isAtLeast`; test all pairs.
3. **Entities:** organization factory; insert-only membership with ids, not associations.
4. **Lookup interface** in the shared package, JPA implementation selecting only the role in a read-only transaction.
5. **Request-scoped context** with enter-once, throwing reads, `requireAtLeast` → 403.
6. **Gate interceptor** for `/organizations/*` and `/organizations/*/**`: only `@Controller` methods; `{orgId}` by name, missing → 500; non-numeric and non-member → the same constant 404; enter the context.
7. **Creation** inserts organization + owner in one transaction; **"mine"** query rooted on the organization with the caller in the `WHERE`; **members** query with the organization from the context, no email; translate sort names after validation.
8. **Error naming:** make the conflict handler read a constraint name from a raw `SQLException` too (commit-time violations).
9. **Tests:** slices mock the lookup and import the context; a probe controller with a right-named and a wrong-named endpoint (+ `OPTIONS`); integration with two organizations and whole-body 404 comparison; `SET CONSTRAINTS ALL IMMEDIATE` in JPA slices; cleanup deletes organizations before users.

---

## Part E — Quick reference

### Endpoints (§3.1)

| Method | Path | Auth | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/organizations` | bearer | 201 `{id, name, slug, role: OWNER, createdBy, createdAt}` + `Location` | 400 `VALIDATION_FAILED` · 401 · 409 `DUPLICATE_SLUG` (or `RESOURCE_CONFLICT` under a race) |
| GET | `/api/v1/organizations?page&size&sort` | bearer | 200 page, **mine only**, with `role`; sort `name`, `createdAt`, `id` | 400 `INVALID_SORT_PROPERTY` · 401 |
| GET | `/api/v1/organizations/{orgId}` | bearer + gate | 200 `{…, role}` | 401 · 404 `ORGANIZATION_NOT_FOUND` |
| GET | `/api/v1/organizations/{orgId}/members?page&size&sort` | bearer + gate | 200 page of `{userId, username, displayName, role, joinedAt}`; sort `username`, `joinedAt` | 400 · 401 · 404 |
| OPTIONS | `/api/v1/organizations/{orgId}` (no `Origin`) | bearer | 200 `Allow: GET,HEAD,OPTIONS` (Spring's answer, not gated) | 401 |

### Errors added

| Code | Status | Raised by | Note |
|---|---|---|---|
| `ORGANIZATION_NOT_FOUND` | 404 | the gate; `OrganizationService.get` | Unknown, non-member and non-numeric alike; detail always "Organization not found" |
| `ORGANIZATION_ROLE_REQUIRED` | 403 | `TenantContext.requireAtLeast` | Property `requiredRole`; only inside your own organization |
| `RESOURCE_CONFLICT` with `constraint` | 409 | the database | `ck_organizations_has_owner`, `uk_organization_memberships_one_owner`, `uk_organization_memberships_org_user`, `uk_organizations_slug` |

### Test tools (§3.0)

`AdjustableClock` (real time + offset, `@Primary` in the shared test config, `reset()` in `@BeforeEach`) · `TestLogins.login/cookieHeader/refreshTokenIn/sessionIdOf` · `Concurrently.run(n, call)` · `ProblemBodies.withoutRequestSpecificFields` · `TestUsers.deleteAll` (organizations first). Patterns and how to read them: `../phase-1/SECURITY_TESTING_GUIDE.md` §8.

---

## Part F — Framework behaviour we relied on (checked in the sources)

| Behaviour | Consequence for the design |
|---|---|
| `DispatcherServlet.doDispatch` calls `applyPreHandle` inside the `try` whose exception goes to `processDispatchResult` (spring-webmvc 6.2.19) | The gate's exceptions become the standard `ProblemDetail` |
| `RequestMappingInfoHandlerMapping.handleMatch` stores `URI_TEMPLATE_VARIABLES_ATTRIBUTE` during handler lookup, before `preHandle` | The gate can read `{orgId}` |
| `handleNoMatch` answers an unmapped `OPTIONS` with `new HandlerMethod(new HttpOptionsHandler(...))` and stores **no** path variables | Gate only `@Controller` methods |
| `RequestMappingHandlerMapping.isHandler` = `AnnotatedElementUtils.hasAnnotation(beanType, Controller.class)`; `HandlerMethod` keeps `ClassUtils.getUserClass(bean)` | The gate's predicate covers every annotated endpoint, proxied or not |
| `@WebMvcTest` includes `HandlerInterceptor` and `WebMvcConfigurer` beans (spring-boot-test-autoconfigure 3.5.16 bytecode) | Every web slice loads the gate |
| A request-scoped bean outside a request → `ScopeNotActiveException` (`AbstractBeanFactory`; *seen* with `@Async`) | Loud failure off the request thread |
| `JpaTransactionManager.doCommit` translates a commit failure's cause by SQLState; a deferred violation has no Hibernate `ConstraintViolationException` in its chain (*seen*) | Read the name from the raw `SQLException` |
| Hibernate 6.6.53 `PostgreSQLDialect`'s extractor takes the name from the message by SQLState template | The trigger's message is worded like PostgreSQL's |
| Spring Data JPA 3.5.13 prefixes sort properties with the root alias unless they start with a join variable | Translate sort names; root "mine" on `Organization` |
| `@DataJpaTest` includes `JdbcTemplateAutoConfiguration`; its `JdbcTemplate` joins the test transaction | `SET CONSTRAINTS ALL IMMEDIATE` works inside the rolled-back test |

---

## Part G — Known gaps, debt and what comes next

- **Owed test:** an `OPTIONS` mapping we write must stay gated (the reason the `@Controller` predicate beats "skip every `OPTIONS`").
- **Nits:** commented-out `findRoleByOrganizationIdAndUserAccountId`; concurrent duplicate slug → generic 409; the role order is the rule; `CommonErrorUtility` builds a `PostgreSQLDialect` to borrow its extractor.
- **📊 Not measured:** the gate lookup's plan with real row counts.
- **Next:** §3.2 (conditional role changes, removal, leave, transfer with re-authentication), §3.3 invitations, §3.4 `@TenantId` on projects (the scope half of A2).

---

## Changelog

| Date | Sub-section | Change |
|---|---|---|
| 2026-10-16 | §3.0, §3.1 | Created: principles A1–A9, architecture, C1–C4, recipe, quick reference, verified framework behaviour, gaps. Covers the `OPTIONS` fix after review and your passing Postman run (23 rows). |
