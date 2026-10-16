# Phase 3 — Organizations & Multi-Tenancy (Requirements)

> Companion to `../PROJECT_CONTEXT.md` · what Phase 2 built and learned: `../phase-2/PHASE_2_LEARNING_LOG.md` (its §8 is the backlog this phase starts from) and `../phase-2/PHASE_2_IMPLEMENTATION_REFERENCE.md` · test patterns: `../phase-0/TESTING_GUIDE.md` and `../phase-1/SECURITY_TESTING_GUIDE.md`. Requirements only, no code (the migration SQL is provided, as agreed). Tick the boxes as you go.
> **Time-box: 8h, tests included. Hard stop at 12h (150%).** The carried test debt is not in it (D1 gives it its own budget). Anything unfinished becomes a side task in Phase 4.
> You run the app from IntelliJ and call the API with Postman; run steps come after each sub-section is built, in Postman form (one collection per user: alice, bob, carol).

## ▶ Where we are (resume here) — §3.1 done 2026-10-16 (not yet committed); §3.2 next

| | |
|---|---|
| **Done** | Phase 2 closed 2026-10-12; §2.4 + §2.5 committed (`b6f3dde`). **§3.0** (2026-10-13, committed `ec5acad`): 18 Tier-1 tests + infrastructure, written by Claude at your request; all 14 mutations caught; suite 101 tests, 14 s, 2 containers. This brief: D1–D8 raised, §3.0–§3.7 written, V7–V9 SQL **verified on a scratch copy of your dev database** (2026-10-13; `taskflow_scratch_p3`, dropped afterwards; your dev DB untouched, still at V6). |
| **§3.1** | ✅ **Done 2026-10-16: your Postman run passed all 23 rows.** Built 2026-10-15, code and tests written by Claude at your request, in the build order. Suite **136 tests, 0 failures, 18.6 s, 2 containers**; 15 of 15 mutations caught; deliberate failures 1–5 observed (results at the end of §3.1). Two gaps found and fixed: a commit-time violation's 409 had no constraint name (`CommonErrorUtility`); a plain `OPTIONS` on an org path was a 500 (review finding, 2026-10-16: the gate now gates only `@Controller` methods). |
| **Next** | 1. **Commit §3.1** (code, V7, tests, docs; staged by name). 2. Your 15-minute Phase 1 and Phase 2 notes (both templates still empty). 3. **§3.2** (roles, removal, leave, transfer), and as you pass: the owed test (an `OPTIONS` mapping we write must stay gated) and the commented-out `findRoleByOrganizationIdAndUserAccountId` (end of §3.1). |
| **Decisions to raise** | None open. D1–D8 all decided 2026-10-13 (the recommendations), so V7–V9 stand as written. OpenAPI moved to Phase 11 (the proposal, taken with the recommendations). |
| **Debt** | Test debt: **Tier 1 paid** (§3.0); Tier 2 → Phase 11 (list in §3.0 *Out*). Phase 2's `prod`-without-key done-item: **proven** (`JwtKeyConfigTest`). README → §3.5. Nits: *Carried in*. |
| **Environment state** | Dev DB at **V7** (applied 2026-10-16 by your app start; backfill: `demo-org9` → alice, `demo-org98` → `abhinavvgargg-jwt`; the 4 `system` organizations stay ownerless and unreachable). Your Postman run may have left `p31…` users and organizations (cleanup SQL at the end of §3.1 *Run it*). The dev signing key is ephemeral. |
| **Numbering** | Decisions are **D1–D8** in this doc (Phase 2's D-numbers are prefixed "Phase 2" when referenced). Migrations: V7 (§3.1), V8 (§3.3), V9 (§3.4); V1–V6 frozen. |

---

**The goal of Phase 3 is a boundary between customers.** Until now there was one pool of data: any signed-in user can list every organization (`GET /api/v1/organizations` returns all of them today) and read any one by id. Phase 3 makes the organization the **tenant**: every request that touches an organization's data names the organization, is checked against the caller's membership, and every query on tenant-owned data is scoped to that organization automatically, so that a forgotten `WHERE` in a later phase can't leak one customer's data to another.

⚠️ **Trap, the one that defines this phase:** "We check membership in the controller, so isolation is done." The membership check protects the **request**; it doesn't protect the **query**. The day someone writes `projectRepository.findById(id)` with an id from the URL, the check passed (you *are* a member of your own organization) and the row came back from someone else's. Every section below is judged by one question: **what does a member of organization A get when they send organization B's ids?** The answer must always be the same 404 they'd get for an id that doesn't exist.

---

## Carried in from Phase 2

| Item | What happens in Phase 3 |
|---|---|
| **Test debt** (Phase 1 D9; Phase 2 D10, D12, D14, D16): reset and change have **no automated test at all**; nothing automated proves rotation, reuse detection, the refresh race or revocation | **D1** (raised first). Recommended: a Tier-1 block before §3.1 (**§3.0**), the rest in Phase 11. |
| §1.7 / §2.6 profile (Phase 2 D17) | **§3.5**, side task, on the trim list. The §2.6 brief is carried over and updated. |
| `prod` without a signing key refuses to start: implemented, never proven | **§3.0** (an `ApplicationContextRunner` test), if D1 = A. |
| README still describes HTTP Basic (6 mentions) | **§3.5**: login, refresh, logout, the cookie, bearer usage, and now organizations. |
| OpenAPI ("due after Phase 2") | **Proposed: Phase 11** (packaging & docs), next to the README and the Postman collection. springdoc is no longer in the `pom.xml`; Phase 3 adds ~15 endpoints and Phase 4 reshapes authorization, so documenting now means documenting twice. **Moved to Phase 11 (2026-10-13).** |
| Your 15-minute notes, Phase 1 and Phase 2 | Before §3.1 (both templates empty). |
| Nits in files you'll touch anyway | Fix as you pass: `Organization`'s public no-arg constructor and setters (it becomes a factory, §3.1); `UserAccount`'s unused `setRole`/`setTimezone` (the profile replaces `setTimezone` with a real method, §3.5); `TestLogins.accessTokenFor`'s raw `Map` (§3.0). The rest of Phase 2's nit list stays in its log (§8 E). |
| Phase 2's rule "owner in the `WHERE`, every miss the same 404" | Becomes the rule for every org-scoped id in this phase: organization, member, invitation, project. |

---

## 🏗️ Decisions — raised first

Each has options, a recommendation, and why the recommendation beats every alternative listed. Choose, or defer one to the start of the sub-section that needs it.

### D1 — When the carried test debt gets paid (needed now)

What's owed (Phase 2 log §8 A): nine rows across §1.3–§1.6 and §2.1–§2.5, Phase 0's "never echo `rejectedValue`" test, and mutation checks for all of it (none since §1.2). **Password reset and change have no automated test at all**, and neither does anything in rotation, reuse detection or revocation.

| Option | When | Cost |
|---|---|---|
| **A. Split it in two tiers. Tier 1 (the tests guarding rules on the never-cut list, plus the test infrastructure Phase 3 needs anyway) in its own block, §3.0, before §3.1, with its own budget (~3h 30m). Tier 2 to Phase 11 ("fill test gaps"). Phase 3's own tests are written in each sub-section, not deferred.** *(recommended)* | Now | ~3h 30m, outside Phase 3's 8h |
| B. All of it before Phase 3 | Now | ~7h: a full week with no feature |
| C. Interleaved: the first 45 minutes of every Phase 3 session | During Phase 3 | Same total, spread out |
| D. Phase 11 | Week 12 | Nothing now |

**Why A beats each alternative.**
- **The pattern so far:** every test list placed *inside* a time-boxed section was cut when the box ran out (Phase 1 D9, then Phase 2 D10, D12, D14, D16). A separate block with its own budget is the only form that isn't competing with the feature for the same hours. That's also **C's concrete failure**: the 45 minutes are the first thing given up the day a §3.x step overruns, and Phase 3 ends with the same debt plus its own.
- **Phase 3 puts a hook into every database session the application opens.** §3.4's tenant-identifier resolver is called by Hibernate whenever a session opens (*verified*: `SessionFactoryImpl`'s session builder calls `resolveCurrentTenantIdentifier()`), including the ones behind login, refresh, password reset and the per-request session check. A resolver bug (a `null` tenant makes Hibernate throw on **every** session, *verified* in `AbstractSharedSessionContract`) breaks exactly the flows that have no tests. Tier 1 is the net that catches it the moment it happens, instead of a manual Postman run noticing later.
- **Phase 3's own tests need the same infrastructure**: an adjustable `Clock` (invitation expiry), a login helper that returns the refresh cookie too (multi-user isolation tests), a parallel-request helper (the accept race). Built once, in §3.0, then reused.
- **B's concrete failure:** a week of tests with no feature, for Tier-2 items (claim contents, CORS property validation, registration details) whose regression risk is low because nothing in Phases 3–4 touches them. That's the week most likely to stall the project.
- **D's concrete failure:** Phase 4 rewrites authorization (method security, a permission evaluator) on top of Phase 2's security code; the "must not be cut" list in `PROJECT_CONTEXT.md` §5 names "Phases 2–3 JWT rotation + tenant isolation · testing at every phase". Nine phases of edits on untested security code, with the first failing test written in week 12.

**A's honest cost:** ~3h 30m before any Phase 3 feature, and Tier 2 stays owed until Phase 11 (the resume bullet "tested" must wait for it). The Tier-1 list is in §3.0.

**Whatever you choose for the debt:** Phase 3's own tests are proposed as **not deferrable**. Tenant isolation is on the never-cut list. If the time-box runs out, trim features (the trim order is at the end), not the isolation tests.

### D2 — The tenancy model (needed by §3.1)

| Option | What it means |
|---|---|
| **A. Shared schema: one set of tables, every tenant-owned row carries `organization_id`** *(recommended)* | One database, one schema, one Flyway history. Isolation is enforced by queries (D4). |
| B. Schema per tenant | A Postgres schema per organization; Hibernate switches schema per request (`MultiTenantConnectionProvider`). |
| C. Database per tenant | A database (and connection pool) per organization. |

**Why A beats B and C.** TaskFlow's organizations are many, small, and created by anyone who signs up (`POST /organizations` is open to every user).
- **B's concrete failure:** every signup runs DDL (create schema + the whole migration set), Flyway must migrate N schemas on each deploy (a failed migration in schema 412 leaves the fleet half-upgraded), and Postgres catalog bloat grows with tenants × tables × indexes. A cross-tenant query (Phase 8's "issues assigned to me across all my organizations") becomes a `UNION` over schemas.
- **C's concrete failure:** a connection pool per tenant: 500 tenants × 10 connections exceeds Postgres's `max_connections` long before 500 customers. It's the model for a few large, regulated tenants who pay for isolation, not for self-service signup.
- **A's honest cost:** isolation is a property of **every query**, so a bug can leak across tenants; one noisy tenant shares indexes and I/O with everyone. That's why D4 exists, and why the isolation tests are never cut.

### D3 — How a request names its organization, and how fresh the membership is (needed by §3.1)

| Option | What it means |
|---|---|
| **A. In the path: `/api/v1/organizations/{orgId}/…`; the caller's membership and role are read from the database on every such request** *(recommended)* | Explicit per request; one unique-index lookup per org-scoped request. |
| B. A header: `X-Organization-Id` | Same lookup; the organization is invisible in the URL. |
| C. In the access token: an `org` claim (and the role), switching organization = a new token | No lookup. |
| D. A subdomain: `acme.taskflow.app` | The organization comes from the host. |
| E. In the path, by slug: `/organizations/{slug}/…` | Readable URLs. |

**Why A beats each alternative.**
- **C's concrete failure:** a removed member keeps working in the organization until their access token expires (up to 15 min), and a demoted admin keeps admin rights for as long. That's Phase 2's D5 argument again: the moment you need revocation is the moment someone is being removed for cause. Adding a per-request check to fix it makes the claim pointless. A user in five organizations with two tabs open on different ones would also need two tokens.
- **B's concrete failure:** the organization isn't in the URL, so access logs, the correlation-id trail and shared links don't show which tenant a request touched; a client bug that sends a stale header from another tab acts **in the wrong organization of the same user**, with no 404 to warn anyone (they *are* a member of both). CORS must also allow the custom header.
- **D's concrete failure:** every tenant is a new origin. Phase 2's CORS design is an exact allow-list validated at startup (`*` refused); per-tenant subdomains need wildcard origin patterns with credentials on `/auth/**`, which is exactly what §2.5 refused. Plus wildcard DNS and TLS. A deployment decision for later, not an API one.
- **E's concrete failure:** slugs are names, and names change (a company renames itself); every bookmarked or stored URL breaks, or you build redirects. The lookup also becomes a join (slug → id → membership). Ids never change; the slug stays a unique human handle for display.
- **A's honest cost:** one indexed lookup on every org-scoped request (the same price as Phase 2's session check, for the same reason: changes take effect on the next request). URLs carry numeric ids; the frontend shows the slug.

### D4 — How isolation is enforced (needed by §3.4)

| Option | What it means |
|---|---|
| A. Explicit only: every repository method takes `organizationId` and puts it in its `WHERE` | Nothing automatic. |
| B. Hibernate `@Filter` / `@FilterDef`, enabled per session | A named SQL condition added to queries when enabled. |
| **C. Hibernate `@TenantId` on tenant-owned entities, the membership gate on every request (D3), and composite foreign keys for references between tenant-owned rows (from Phase 4 on)** *(recommended for this project; labelled the compromise against D)* | Hibernate's built-in discriminator multi-tenancy. |
| D. PostgreSQL row-level security (RLS) under C: policies on each tenant table, `SET LOCAL app.org_id` per transaction | The database itself refuses other tenants' rows. |

**What was verified** (Hibernate 6.6.53 sources, 2026-10-13):
- `@TenantId` is implemented **as a filter** named `_tenantId` with the condition `<column> = :tenantId` (`TenantIdBinder`), declared with **`applyToLoadByKey = true`**: unlike a plain filter, it also applies to `find()` by id and to fetching a to-one association.
- The filter is **switched on when the session opens**, with the value from your `CurrentTenantIdentifierResolver` (`AbstractSharedSessionContract.setUpMultitenancy`), unless the resolver says the value is a "root" tenant (`isRoot`), which sees everything.
- If any entity has `@TenantId`, **every session needs a non-null tenant id**, or Hibernate throws `"SessionFactory configured for multi-tenancy, but no tenant identifier specified"`.
- On insert, a null tenant attribute is **filled in from the session**; an explicitly set value that differs from the session's tenant is **refused** (`PropertyValueException`), unless the session is root (`TenantIdGeneration`). The attribute is made non-updatable.
- A plain `@FilterDef` does **not** apply to lookups by primary key unless `applyToLoadByKey = true` (still `@Incubating`), and has no effect until enabled (or declared `autoEnabled`).
- Filters are applied to HQL/criteria `SELECT`s **and** to bulk HQL `UPDATE`/`DELETE` (the root table group gets the enabled filters' restrictions in `BaseSqmToSqlAstConverter`; *read in the source, not run*). They never reach **native SQL**, which Hibernate doesn't translate.
- `@Where` is **deprecated since 6.3** (replaced by `@SQLRestriction`): a fixed, always-on, unparameterised condition. It can't carry "the current tenant".

**Why C beats A and B.**
- **A's concrete failure:** Phases 4–8 add dozens of queries on tenant data (projects, issues, comments, watchers, history), and Phase 8 builds queries **at runtime** from Specifications for dynamic search: the place a `WHERE organization_id = ?` is most likely to be forgotten. One forgotten condition is a cross-tenant leak, and it passes every test that uses a single organization.
- **B's concrete failures:** a plain filter ignores `findById` (*verified*), so `GET /organizations/A/projects/{B's id}` returns B's project while the list endpoint looks correctly filtered; it must be enabled on **the session your query runs in**, and with `open-in-view=false` each transaction opens its own session, so the tutorial approach (enable it in a web interceptor) enables it on a session your service never uses; and it doesn't set `organization_id` on insert. §3.4's deliberate failure 1 lets you see the `findById` hole.
- **C's honest costs and holes (the compromise, stated):** native SQL and plain JDBC bypass it; every session needs a tenant id, so non-tenant requests (login, refresh, "my organizations") need a **"no tenant" value** that matches nothing; the tenant is fixed **when the session opens**; a "root" escape hatch must never be reachable by accident; and it guards **reads and inserts, not references**: a write that points at another tenant's row by id (`getReferenceById(otherTenantsId)` assigned to a relation) isn't checked. Composite foreign keys close that in the database from Phase 4 on (V7's `unique (organization_id, user_account_id)` is already the first target). Access tables (memberships, invitations) aren't tenant-filtered (§3.1 explains why) and rely on explicit keys plus tests.

**Why D is the strongest, and why it isn't the recommendation here.** RLS is enforced by PostgreSQL for **every** path: native SQL, JDBC, a reporting job, a developer in `psql` with the application's credentials. It's the "enforced where it can't be bypassed" answer. Its costs, concretely: the application must connect as a **role that doesn't own the tables and has no `BYPASSRLS`** (table owners and superusers skip RLS), so Flyway (owner) and the app (restricted) need separate credentials in every environment; each transaction must run `SET LOCAL app.org_id = …` before its first query; policies are needed for the legitimate cross-tenant reads (the gate's own lookup, "my organizations", "my invitations"); and **Testcontainers connects as a superuser, which bypasses RLS: tests pass with RLS silently off** unless the test datasource uses the restricted role. That's +2–3h and a second database role everywhere, which is disproportionate inside an 8h phase. So: **C is the recommendation for this project, and D is offered as §3.6, optional, first on the trim list**, with its design written down either way (it's a strong interview answer).

### D5 — Where the current tenant lives during a request (needed by §3.1)

| Option | What it means |
|---|---|
| **A. A request-scoped bean (`TenantContext`, `@RequestScope`, injected into singletons through a scoped proxy), filled by the gate after the membership check** *(recommended)* | Spring creates one per HTTP request and discards it when the request ends. |
| B. A static `ThreadLocal` holder set in the interceptor's `preHandle` and cleared in `afterCompletion` | Works on any thread, request or not. |
| C. No ambient state: the organization id is passed explicitly through every method | Nothing hidden. |

**Why A beats each alternative.**
- **B's concrete failure:** Tomcat reuses threads. Any path that skips the `clear()` (an exception thrown before the interceptor stack completes, an early `return false`, a second `DispatcherServlet`, a code path someone adds outside the interceptor) leaves the previous request's tenant on the thread, and **the next request on that thread runs as another organization**: silent, intermittent, load-dependent, and the worst bug this phase can have. With `@Async` (Phase 9), a plain `ThreadLocal` is empty in the pool thread; an `InheritableThreadLocal` is worse: pool threads keep the tenant of whichever request created them. §3.4's deliberate failure 3 shows the leak with one Tomcat thread.
- **C's concrete failure:** Hibernate's `CurrentTenantIdentifierResolver` is called by Hibernate with no arguments when a session opens: it can only read ambient state. C would still need A or B for D4, and every service signature in Phases 4–8 grows an `organizationId` parameter that someone eventually passes from the wrong variable.
- **A's honest costs:** outside a request the bean doesn't exist; touching it throws `ScopeNotActiveException` (*verified* in `AbstractBeanFactory`; the cause is `RequestContextHolder`'s "No thread-bound request found"). That's the right failure (loud), but the Hibernate resolver must check for an active request **first** and answer "no tenant", or every `@Scheduled` job and every JPA slice test fails at session open. Phase 9's async work must carry the organization id explicitly and open an explicit tenant scope (designed then). JPA slice tests set a tenant by activating a request (`RequestContextHolder.setRequestAttributes(...)`) or test through HTTP.

### D6 — Organization roles and who may do what (needed by §3.1, §3.2)

| Option | What it means |
|---|---|
| **A. Exactly one OWNER; ADMIN; MEMBER. You may act only on members strictly below you, and grant only roles strictly below yours. Ownership moves only by transfer.** *(recommended)* | Owner: everything. Admin: invite members, remove members, revoke invitations, create projects. Member: read. |
| B. "At or below": admins can invite, promote and remove admins | Admins are peers. |
| C. Several owners (GitHub-style), all equal | No single owner. |

**Why A beats B and C.**
- **B's concrete failure:** one compromised or disgruntled admin account promotes an accomplice and removes every other admin within a minute, and nothing in the rules stops it; two admins can demote each other in a loop. "Strictly below" makes privilege escalation impossible by construction: nobody can create a peer.
- **C's concrete failure:** any owner can remove the others, so the rogue-admin problem moves to the top, and "the last owner can't leave" logic is still needed. Transfer has no meaning.
- **A's honest cost:** the owner is a single point: if they leave the company without transferring, nobody inside the organization can administer it (support tooling, not built). Deleting the owner's user account is refused by the database while they own an organization (*verified*: the V7 trigger fires on the cascaded delete).

**The full matrix** (a rule, not a list of special cases: "strictly below"):

| Action | OWNER | ADMIN | MEMBER |
|---|---|---|---|
| See the organization, its members, its projects | ✅ | ✅ | ✅ |
| Invite as MEMBER | ✅ | ✅ | ❌ 403 |
| Invite as ADMIN | ✅ | ❌ 403 | ❌ 403 |
| List and revoke open invitations | ✅ | ✅ | ❌ 403 |
| Change a member's role (ADMIN ↔ MEMBER) | ✅ | ❌ 403 | ❌ 403 |
| Remove a MEMBER | ✅ | ✅ | ❌ 403 |
| Remove an ADMIN | ✅ | ❌ 403 | ❌ 403 |
| Leave | ❌ 409 (transfer first) | ✅ | ✅ |
| Transfer ownership (with current password) | ✅ | ❌ 403 | ❌ 403 |
| Create a project (§3.4) | ✅ | ✅ | ❌ 403 |

### D7 — How an invitation is accepted (needed by §3.3)

| Option | What it means |
|---|---|
| **A. Bound to the email, no token: the signed-in user whose verified email equals the invitation's sees it in `GET /users/me/invitations` and accepts or declines it by id** *(recommended)* | The email is the address; signing in is the proof. |
| B. A token (hashed, single-use, Phase 1's machinery) **and** the email binding | A link with a secret, which also checks the email. |
| C. A token only: whoever holds the link joins | The classic "join link". |

**Why A beats B and C.**
- **C's concrete failure:** invitation emails get forwarded, pasted into tickets and chat, and land in browser history and proxy logs as URLs. Whoever holds the link joins a private organization, with whatever role the inviter chose, ADMIN included.
- **B adds a credential and buys nothing.** Logging in already requires a verified email (Phase 1: unverified login is refused), so "signed in as the invited address" already proves the inbox. The token is one more secret that can leak, one more column, hash and expiry path, for the same guarantee. Phase 1 already taught token design three times.
- **A's honest costs:** no one-click "join" for someone who isn't signed in: the (future) email links to the app's invitations page, and the invitee signs up or signs in first. The invitee must use the invited address (someone with two addresses is re-invited). Invitation emails stay deferred (`PROJECT_CONTEXT.md` §6), so for now the invitee is told out of band or checks the page.
- If you choose A, `PROJECT_CONTEXT.md` §2.1 ("token returned in the response for now") is updated to match. If you choose B, V8 gains a `token_hash` column and I revise the SQL before you apply it.

### D8 — Which tenant-owned data Phase 3 isolates (needed by §3.4)

| Option | What it means |
|---|---|
| **A. Pull a minimal Project forward from Phase 4: create, list and get, with a key unique per organization, as the first `@TenantId` entity. Phase 4 keeps project membership, project roles, settings and archiving.** *(recommended)* | Phase 3 proves isolation on real tenant data. |
| B. Build the tenant filter with no entity using it; prove it in Phase 4 | Phase 3 stays exactly as planned. |
| C. Make invitations the tenant-owned entity | Uses what Phase 3 already has. |

**Why A beats B and C.**
- **The problem:** Phase 3's own tables aren't tenant-owned data. Memberships and invitations are **access tables**, read from both sides: by the organization (members list) and by the user across organizations ("my organizations", "my invitations"), and the gate reads memberships *before* the tenant is known. None of them can carry `@TenantId` (§3.1, §3.3 explain).
- **B's concrete failure:** the filter's wiring (resolver, "no tenant" value, session timing) ships untested and is first exercised in Phase 4, together with the permission evaluator: two hard mechanisms debugged at once, in the phase the plan calls the most important. Phase 3's "tenant isolation" would have nothing to prove.
- **C's concrete failure:** the invitee side ("my invitations", accept) reads across organizations, so it would need a **root** session: Phase 3 would introduce the bypass in the same phase as the wall, and every later developer would copy the pattern.
- **A's honest cost:** about an hour moves from Phase 4 into Phase 3 (Phase 4 gets lighter by the same amount). `PROJECT_CONTEXT.md` §5's Phase 3 and 4 rows are updated to match.

---

## Decisions (raised and decided 2026-10-13: every recommendation taken)

| # | Decision | Chosen | Options considered · the reasoning |
|---|---|---|---|
| D1 | **When the carried test debt is paid** | ✅ **A** (2026-10-13): Tier 1 in §3.0 before §3.1 (**written by Claude at your request**, same day; done); Tier 2 in Phase 11; Phase 3's own tests not deferrable | A · all now · interleaved · Phase 11. Tests placed inside time-boxed sections were cut five times; §3.4 hooks every Hibernate session, including the untested login/refresh/reset flows. |
| D2 | **Tenancy model** | ✅ **Shared schema + `organization_id`** (2026-10-13) | shared · schema per tenant · database per tenant. Self-service signup: DDL per signup and N-schema migrations, or a pool per tenant, don't fit. |
| D3 | **How a request names its organization** | ✅ **Path `/organizations/{orgId}/…`, membership read per request** (2026-10-13) | path id · header · token claim · subdomain · path slug. Removal and demotion act on the next request; the tenant is visible in every URL and log. |
| D4 | **Isolation enforcement** | ✅ **`@TenantId` + membership gate + composite FKs** (the compromise); RLS optional in §3.6 (2026-10-13) | explicit only · `@Filter` · `@TenantId` · RLS. `@TenantId` covers `find()` and inserts (verified); a plain filter doesn't. RLS is strongest but needs a second DB role and fails open in Testcontainers. |
| D5 | **Tenant context holder** | ✅ **Request-scoped bean** (2026-10-13) | request scope · `ThreadLocal` · explicit parameters. The container ends it with the request: no tenant left on a pooled thread. |
| D6 | **Roles** | ✅ **One owner; act on and grant only strictly below you; transfer only** (2026-10-13) | strictly below · at or below · several owners. Nobody can create a peer, so no escalation. |
| D7 | **Invitation acceptance** | ✅ **Email-bound, no token, accept by id from "my invitations"** (2026-10-13) | email-bound · token + email · token only. A forwarded link can't let a stranger in; login already proves the inbox. |
| D8 | **First tenant-owned data** | ✅ **A minimal Project pulled from Phase 4** (2026-10-13) | project · filter with no entity · invitations. Memberships and invitations are access tables read from both sides. |

---

## Concepts you need first (the *why*, briefly)

💡 **Tenant, membership, and the two halves of isolation.** A **tenant** is the customer whose data must never be seen by another customer: here, an organization. A **membership** says a user belongs to a tenant, with a role. Isolation has two halves that fail independently: **the gate** (may this caller act in organization 42 at all?) and **the scope** (does every query in this request only see organization 42's rows?). The gate without the scope leaks through ids from URLs; the scope without the gate lets anyone pick any organization. Phase 3 builds both.

💡 **Request scope and scoped proxies.** A singleton service can't hold "the current organization": it's shared by every request. A `@RequestScope` bean is created once per HTTP request. To inject it into a singleton, Spring injects a **proxy** that, on each call, looks up the *current request's* instance (through `RequestContextHolder`, which is itself a `ThreadLocal` that Spring sets and clears around each request). That's why it fails outside a request, and why it can't leak into the next one.

💡 **Discriminator multi-tenancy in Hibernate.** One column (`organization_id`) says which tenant owns a row. With `@TenantId`, Hibernate asks your resolver for the current tenant **once, when a session opens**, then adds `organization_id = ?` to every query on those entities, fills the column on insert, and refuses inserts for another tenant. With `open-in-view=false` (Phase 0), a session lives for one transaction, so "when the session opens" means "when the `@Transactional` method starts".

💡 **Deferred constraints.** Most constraints are checked at each statement. A **deferrable, initially deferred** constraint (here, a constraint trigger) is checked at **commit**. That's how "an organization always has exactly one owner" can be enforced while a transfer passes through a moment with zero owners. Consequence for tests: a test that **rolls back** never reaches commit, so a deferred check never runs unless the test says `SET CONSTRAINTS ALL IMMEDIATE` (*verified*, 2026-10-13).

💡 **404, not 403, across tenants.** Ids come from one global sequence (Phase 0): they're guessable. A 403 for "exists, but not yours" confirms existence. Every miss across a tenant boundary is the **same** 404 as an id that doesn't exist (Phase 2's IDOR rule). A 403 is right only **inside** an organization you belong to ("you're a member, but your role can't do this"): you already know it exists.

---

## How each section is laid out

Same as Phases 1–2 (`PROJECT_CONTEXT.md` §3.1): what → how & why (choices, alternatives not taken) → optimising for → concepts → requirements (grouped by class, each group ending in **Done when**) → traps → deliberate failures → build order → tests.

**Packages this phase creates** (so every class below has a home):

| Package | Holds | Why there |
|---|---|---|
| `common/tenant` | `OrganizationRole`, `TenantContext`, `MembershipLookup` (interface), `TenantGateInterceptor`, `TenantWebConfig`, `TenantIdentifierResolver`, `TenantHibernateConfig`, `TenantErrorCode` | Every tenant-owned feature (projects now, issues later) uses them, and `common` never imports a feature (Phase 2 rule A10): the gate needs memberships, so it calls an interface that `organization` implements, exactly like `SessionStatus`. |
| `organization` | `Organization`, `OrganizationMembership`, repositories, `JpaMembershipLookup`, `OrganizationService`, `MembershipService`, controllers, DTOs | The organization feature, including its access table. |
| `organization.invitation` | `OrganizationInvitation`, repository, `InvitationService`, both invitation controllers, DTOs | Its own lifecycle and table. |
| `project` | `Project`, repository, service, controller, DTOs, `ProjectErrorCode` | Phase 4 grows it. |

---

## 3.0 — Carried test debt, Tier 1 ✅ done 2026-10-13 (written by Claude at your request; 18 tests, all 14 mutations caught)

### What we're building

Automated proof for the security rules that are on the never-cut list or have no test at all, and the shared test tools Phase 3 needs anyway. No production code changes, except fixing what a test exposes.

- **In:** the test infrastructure; the Tier-1 tests below; a mutation check for each.
- **Out (Tier 2, → Phase 11):** §1.3 registration details (duplicate 409 under a race, the password never echoed), §1.4 verification details, §2.1 claim contents, cookie attributes and `no-store`, §2.2 `alg: none` / wrong key / **wrong `aud`** (the one Phase 2 done-item never exercised) / converter rejections / the expiry boundary, §2.5 `CorsProperties` and the preflight table, Phase 0's `rejectedValue` test.

### How we're building it, and why

| Choice | Problem it solves | What it avoids |
|---|---|---|
| **An adjustable `Clock` as a `@Primary` bean in the shared `TestcontainersConfiguration`**, reset in each test's `@BeforeEach` | Lockout expiry, refresh grace, reuse after the window, invitation expiry: tested in milliseconds, without `sleep` | A second application context (a new `@TestConfiguration` on one class starts a second app and a second container, testing guide §7); flaky sleeps |
| **Extend `TestLogins`** to return a typed result: the access token **and** the refresh cookie value | Refresh, logout and multi-device tests | Parsing `Set-Cookie` in every test; the raw `Map` nit |
| **A parallel-request helper**: `ExecutorService` + a `CountDownLatch` releasing N requests at once, returning the statuses | The refresh race, the reset race, §3.3's accept race | Sequential "races" that never overlap |
| **Assert on counts, never on which thread won** | Deterministic race tests | A test that passes or fails depending on scheduling |
| **`ApplicationContextRunner`** for "prod without a key" | Starts only `JwtProperties` + `JwtKeyConfig` with the `prod` profile | The missing `EmailSender` failing first (why the manual run never proved it) |

| Alternative | Why not | The concrete problem it would cause later |
|---|---|---|
| `Thread.sleep(11_000)` to pass the reuse grace | Slow and timing-dependent | The suite grows by minutes per phase; CI flakes on a loaded runner, and someone adds `@Disabled` |
| `@MockitoBean Clock` in each test class | Every distinct `@MockitoBean` set is a new context | A third, fourth, fifth Postgres container (Phase 0 measured the cost) |
| A unit test of `SessionService.refresh` with mocks for the race | Mocks don't lock rows | "Exactly one winner" depends on PostgreSQL's row lock; only the real database proves it |

### Requirements

#### Test infrastructure

- [x] A mutable `Clock` bean, `@Primary`, in `TestcontainersConfiguration`: **`AdjustableClock`**, real time plus an offset, with `advance(Duration)` and `reset()`. *(Changed from the brief's `set(Instant)`: a clock that keeps ticking leaves every existing test behaving exactly as with `Clock.systemUTC()`; a fixed instant would freeze time for tests that never asked for it.)*
- [x] Every integration test class that moves the clock resets it in `@BeforeEach`.
- [x] `TestLogins.login(...)` returns `LoginTokens(accessToken, refreshToken)` (no raw `Map`), plus `cookieHeader`, `refreshSetCookie`, `refreshTokenIn` and `sessionIdOf` (the `sid` claim).
- [x] A helper runs N copies of a request at the same moment and returns the N results: **`Concurrently.run(n, call)`** (two latches). Plus `ProblemBodies.withoutRequestSpecificFields` for "indistinguishable" assertions.
- [x] The container count is still 2. 📊 **Before: 83 tests, 12 s, 2 containers. After: 101 tests, 14 s, 2 containers** (2026-10-13). The three race-test classes passed 5 repeated runs.

**Done when:** an existing integration test still passes with the new clock bean, and the suite still starts 2 containers.

#### Tier-1 tests (what each must prove → the mutation that must turn it red)

| # | Test (kind) | Must prove | Plant this; it must fail |
|---|---|---|---|
| 1 | Session revoked → 401 (integration) | Revoke a session by SQL; the next bearer request with its token is 401 `INVALID_ACCESS_TOKEN` | The converter skips `SessionStatus` |
| 2 | Rotation and reuse (integration, clock) | Refresh rotates the cookie; the old cookie after the grace → 401, session `REFRESH_TOKEN_REUSE`, one `REFRESH_TOKEN_REUSED` event, and the winner's new token is dead too | The reuse branch throws instead of returning |
| 3 | Superseded within the grace (integration, clock) | The old cookie within the grace → 401, **no** clearing `Set-Cookie`, session still live | The grace measured from `created_at`; superseded clears the cookie |
| 4 | The refresh race (integration, parallel) | 20 refreshes with one cookie → exactly one 200, nineteen 401, no 500 | Check-then-act consume |
| 5 | Dead-session refresh (integration) | Refresh on a revoked session twice → 401 both times, token unconsumed, **no** reuse event (Phase 2 D13) | Remove the `setRollbackOnly()` |
| 6 | Reset revokes everything (integration) | Two devices; reset → both access tokens 401, both refreshes 401, sessions `PASSWORD_RESET`; the reset token works once, and 20 parallel uses → one success | Reset without `revokeAll`; the token's consume ignoring its row count |
| 7 | Change keeps the current session (integration) | Two devices; change on device 1 → device 1 still 200, device 2 401 `PASSWORD_CHANGED`; a wrong current password → 400 `CURRENT_PASSWORD_INCORRECT` (`field: currentPassword`) and counts toward lockout | Change revokes the current session too |
| 8 | The lockout rollback trap (integration, clock) | 4 wrong passwords → counter 4 **and 4 `LOGIN_FAILED` events**; the 5th locks (`ACCOUNT_LOCKED`); the correct password → still 401; clock past `locked_until` → 200 | Make `LoginService.login` `@Transactional` |
| 9 | Session IDOR (integration) | Another user's session id → 404 byte-identical (bar timestamp, correlation id, path) to a random id's, their session still live; `DELETE /users/me/sessions/` revokes nothing | `revokeOwned` without the owner in the `WHERE` |
| 10 | Enumeration (integration) | Unknown email, wrong password, locked account → identical 401 bodies; reset for an unknown email → 202 and **no** email captured (and a known one → an email: the positive pair) | A distinct detail for locked accounts *(corrected: unknown emails already look like bad credentials inside `DaoAuthenticationProvider`, so the realistic leak is the locked branch)* |
| 11 | `prod` without a key (`ApplicationContextRunner`) | Context fails, and the failure names the key properties; an unknown profile (`staging`) fails too; with `dev`, it starts with an ephemeral key | Remove the refusal; a block-list (`!prod`) instead of the allow-list |

**Done when:** all 11 pass, each mutation turned at least one red (recorded in the learning log with the failing assertion), and the suite count and time are recorded. ✅

**Where they live** (all with the shared integration configuration, so one context and one container):

| Class | Tests | Rows above |
|---|---|---|
| `user/session/SessionRevocationIntegrationTest` | 3 | 1, 9 |
| `user/session/RefreshIntegrationTest` | 4 | 2, 3, 4, 5 |
| `user/PasswordSessionsIntegrationTest` | 5 | 6, 7 |
| `user/LoginLockoutIntegrationTest` | 3 | 8, 10 |
| `common/security/JwtKeyConfigTest` (no Spring test context) | 3 | 11 |

**Mutation results** (planted one at a time in a scratch copy of the project, deleted afterwards; 2026-10-13):

| # | Planted | Went red | The failing assertion |
|---|---|---|---|
| 1 | Converter skips the session check | `revokedSession_itsAccessToken_isRejectedOnTheNextRequest` | expected 401, was **200** |
| 2 | Reuse branch throws | `reusingARotatedToken_afterTheGrace_…` | expected 401, was **500** (and the revocation rolled back) |
| 3a | Grace measured from `created_at` | `reusingARotatedToken_withinTheGrace_isSuperseded_notTheft` | expected no `Set-Cookie`, got a clearing cookie (treated as theft) |
| 3b | Superseded clears the cookie | same | same |
| 4 | Check-then-act consume | `twentyParallelRefreshes_…` | expected nineteen 401s, got **10** |
| 5 | No `setRollbackOnly()` | `refreshOnARevokedSession_…` | `consumed_at` expected null, was a timestamp |
| 6a | Reset without `revokeAll` | `reset_endsEverySession_…` | expected 401, was **200** |
| 6b | Reset token consume ignores its row count | `resetToken_worksOnlyOnce`, `…TwentyTimesAtOnce…` | 204 on reuse; **20 of 20** parallel uses succeeded |
| 7 | Change revokes the current session too | `change_keepsTheChangingDevice_…` | expected 200, was 401 |
| 8 | `login` made `@Transactional` | `fiveWrongPasswords_…` | `LOGIN_FAILED` events expected 4, were **0** (the counter survived: it commits in `REQUIRES_NEW`) |
| 9 | `revokeOwned` without the owner | `anotherUsersSessionId_…` | expected 404, was **204** (bob logged out by alice) |
| 10 | Locked account gets its own detail | `unknownEmail_wrongPassword_andLockedAccount_…` | the bodies differ in `detail` |
| 11a | No refusal (ephemeral key everywhere) | `prod_withoutAKey_…`, `anUnknownProfile_…` | the context started |
| 11b | Block-list `!prod` | `anUnknownProfile_withoutAKey_refusesToStart_too` | the context started for `staging` |

### Traps ⚠️

- **A `@Primary` clock in the shared config moves time for every test in that context.** Reset it in `@BeforeEach`, or a test that advanced it 31 minutes breaks the next class.
- **Integration data commits** (testing guide §6.5): clean up in `@BeforeEach`. From §3.1 on, delete **organizations before users**: the owner invariant refuses to delete a user who owns an organization (*verified*: the cascade fires the V7 trigger, and the commit fails). `TaskflowSecurityIntegrationTest` deletes only users today: it will fail once any test leaves an organization behind.
- **A race test that awaits nothing proves nothing:** release all requests from one latch, and wait for all futures before asserting.
- **The rollback trap is about what survives the 401**: assert the **database**, not just the status. *(Corrected 2026-10-13: my brief said "the counter"; but the counter commits in `LoginAttemptService`'s own `REQUIRES_NEW` transaction, so a transactional login can't roll it back. What it does roll back is the `LOGIN_FAILED` history (mutation 8: 0 events instead of 4). The test asserts both.)*
- **The test client keeps no cookies** (*verified*: Boot 3.5.16 picks the JDK `HttpClient` when no Apache/Jetty/Reactor client is on the classpath, and configures no cookie handler). Send `Cookie: taskflow_refresh=…` yourself; that's also what lets a test present an OLD cookie on purpose.
- **Moving the clock moves the access token's expiry too** (the decoder's timestamp validator uses the same clock). Advancing 11 s is harmless; advancing 15 min kills every bearer token you obtained before: log in after moving it.

### Deliberate failures

The mutation column above *is* this section's list: each planted bug is a deliberate failure, and each must be seen red.

### Build order

1. Infrastructure; prove the context count is unchanged.
2. Tests 1, 6, 7 (revocation: the code §3.x's resolver will sit under).
3. Tests 2–5 (refresh).
4. Tests 8–10 (Phase 1's debt).
5. Test 11.
6. Mutation pass over all 11.

---

## 3.1 — Memberships and the tenant boundary

### What we're building

An organization now has members. Creating an organization makes you its **owner**. `GET /api/v1/organizations` lists **your** organizations with your role in each (today it lists everyone's). Every request to `/api/v1/organizations/{orgId}` or below passes a **gate**: the caller's membership in that organization is read from the database; a non-member gets the same 404 as for an organization that doesn't exist; a member's organization and role are put in a request-scoped `TenantContext` for the rest of the request. Members can list the organization's members.

- **In:** V7 (memberships, the owner invariant, the backfill), `OrganizationRole`, `TenantContext`, `MembershipLookup`, the gate interceptor, organization creation with an owner, "my organizations", get one organization, list members.
- **Out:** changing roles, removing, leaving, transferring (§3.2); invitations (§3.3); the Hibernate tenant filter (§3.4); renaming or deleting an organization, organization settings (default workflow: Phase 6, when workflows exist).

### How we're building it, and why

**The moving parts**

1. **V7**: `organization_memberships` (one row per organization × user, with the role), a partial unique index (**at most one** owner), a deferred constraint trigger (**at least one** owner, checked at commit), and a backfill of owners for existing organizations.
2. **`OrganizationRole`** (`common/tenant`): `OWNER`, `ADMIN`, `MEMBER`, with `isAbove(other)` (D6's "strictly below" rule lives here, once).
3. **`OrganizationMembership`** (entity, `organization`): `organizationId` and `userAccountId` as plain `Long`s (like Phase 2's `UserSession.userAccountId`: every operation works by id), a role, `BaseEntity` audit columns. **Insert-only from Java**: role changes are conditional updates (§3.2), Phase 2's D11 pattern.
4. **`MembershipLookup`** (interface, `common/tenant`) → **`JpaMembershipLookup`** (`organization`): "what is user U's role in organization O?", one query on the unique index.
5. **`TenantContext`** (`common/tenant`, `@RequestScope`): holds the organization id and the caller's role for this request; set once; `requireAtLeast(role)` throws 403.
6. **`TenantGateInterceptor`** (`common/tenant`, a `HandlerInterceptor`) registered by **`TenantWebConfig`** for `/api/v1/organizations/*` and `/api/v1/organizations/*/**`: reads the `orgId` path variable, asks `MembershipLookup`, fills `TenantContext` or throws the 404.
7. **`OrganizationService`**: `create` (organization + owner membership, one transaction), `listMine`, `get`; **`MembershipService.listMembers`**.

**The request, end to end**

```
security chain: bearer token → AuthenticatedUser (Phase 2, unchanged)
DispatcherServlet: handler found for /api/v1/organizations/{orgId}/members → path variables stored on the request
TenantGateInterceptor.preHandle:
  orgId = path variable "orgId" (missing → 500: a controller named it differently; non-numeric → 404)
  role  = MembershipLookup.roleOf(orgId, user.id)       one unique-index lookup, its own read-only transaction
  none  → 404 ORGANIZATION_NOT_FOUND                      same body as a non-existent organization
  else  → TenantContext.enter(orgId, role)
controller → service: reads the organization id from TenantContext (never from the path variable), checks the role it needs
```

#### The choices

| Choice | Problem it solves | What it avoids |
|---|---|---|
| **Gate in a `HandlerInterceptor`**, after the security chain and after handler mapping | The path variables are parsed and the principal is known; exceptions thrown in `preHandle` go through `@RestControllerAdvice` (same `ProblemDetail` body) | Parsing URLs by hand in a servlet filter; a check repeated in every controller |
| **The gate reads the variable by its name, `orgId`, and fails closed** if it's missing | One convention for every org-scoped controller | A controller mapped as `/organizations/{id}/…` silently skipping the gate (the existing controller uses `{id}`: renamed) |
| **Non-member = 404 `ORGANIZATION_NOT_FOUND`, identical to a non-existent id** | Org ids are guessable (global sequence) | Confirming which organizations exist (Phase 2's IDOR rule) |
| **Services take the organization id from `TenantContext` only** | One source of truth: the value the gate checked | A service using a path variable or a body field that the gate never saw |
| **Membership and role read per request** ↺ D3 | Removal and demotion act on the next request | Stale roles in tokens |
| **Partial unique index: one owner per organization** (immediate) | Two owners can never exist, even for an instant | A transfer bug or race creating co-owners |
| **Deferred constraint trigger: every organization has an owner at commit** | Nothing can commit an ownerless organization: a forgotten membership insert at creation, an owner removed or demoted, an owner's account deleted (*all verified*, 2026-10-13) | "At least one owner" living only in Java, where the next code path (account deletion, an admin tool) forgets it |
| **The trigger's error worded like PostgreSQL's own check message, `errcode = check_violation`** | Hibernate extracts the name from the message for SQLState 23514 (*verified* in `PostgreSQLDialect`), so the existing 409 handler names `ck_organizations_has_owner` | A 409 with no constraint name, or a 500 |
| **Memberships are not `@TenantId`** | The gate's lookup runs before the tenant is known; "my organizations" reads across tenants | A root-tenant bypass in the most sensitive query of the phase |
| **Organization creation inserts the owner membership in the same transaction** | The trigger is satisfied at commit | An ownerless organization if the second insert fails |
| **Insert-only membership entity; changes by conditional `UPDATE`** (Phase 2 D11) | No entity flush can write a stale role back | A transfer and a role change racing, the loser's entity overwriting the winner |
| **"My organizations" lists only memberships; sorted `name` asc, `id` asc through `PageableFactory`** | Every list is paginated and stably sorted (Phase 0 standard) | Today's leak: every organization to every user |

#### Alternatives we didn't take

| Alternative | Why not | The concrete problem it would cause later |
|---|---|---|
| The gate as a Spring Security `AuthorizationManager` on `/organizations/{orgId}/**` | It *is* an authorization rule, and it's Phase 4's tool (method security, permission evaluator) | Two half-built authorization mechanisms before Phase 4 designs one; the request-scoped context must be filled after authorization anyway |
| The check inside each service method ("load the membership first") | Repeated in every method | One forgotten call in Phase 5's comment service = a member of A commenting on B's issue |
| A servlet `Filter` for the gate | Runs before handler mapping: no parsed path variables, and exceptions bypass `@RestControllerAdvice` | Hand-written URL parsing that disagrees with the router on edge cases (trailing slashes, encoded segments), and a second error format |
| 403 for "exists, but you're not a member" | Honest-sounding | An enumeration oracle for organization ids |
| Org role in the JWT ↺ D3-C | No lookup | A removed admin keeps admin rights for 15 minutes |
| `role` as a Postgres enum type | Stronger typing | Adding a role later means `ALTER TYPE` (can't run in a transaction on older versions, awkward in Flyway); the project's convention is `varchar` + `CHECK` (V2, V4) |
| Composite primary key `(organization_id, user_account_id)`, no `id` | Natural key | `@EmbeddedId` ceremony in every repository method; every other entity extends `IdentifiedEntity`. The unique constraint gives the same guarantee and the same index |
| Deleting ownerless legacy organizations in V7 | "Clean data" | A migration that deletes rows runs in every environment; the 4 ownerless dev organizations are unreachable anyway (no member) |

### What we're optimising for

**No path to another tenant's data that isn't a 404**, **invariants the database enforces** (one owner, always), and **one way to know the current tenant** (the context the gate filled). We trade one indexed lookup per org-scoped request and a trigger.

### Concepts

💡 **Interceptor vs filter vs `AuthorizationManager`.** A servlet **filter** runs around everything, before Spring MVC knows which controller will run. A Spring Security **`AuthorizationManager`** runs inside the security chain (Phase 4). A **`HandlerInterceptor`** runs after the controller is chosen and the path variables are parsed (*verified*: `RequestMappingInfoHandlerMapping.handleMatch` stores them in the request attribute `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE` during handler lookup, before `preHandle`). Pick the latest point that has everything you need.
💡 **Why a constraint trigger and not a `CHECK`.** A `CHECK` sees one row. "Every organization has an owner" is about *other* rows. A trigger can look at other rows, and a **constraint** trigger can be **deferred** to commit.
💡 **Partial unique index.** `unique (organization_id) where role = 'OWNER'`: uniqueness only among the rows matching the predicate. Your §1.4 pattern (`uk_user_tokens_active`) again, now for "one owner".

### Requirements

#### The migration (V7): you apply it; the SQL is below

- [x] `V7__create_organization_memberships.sql` is added exactly as given, and the app starts with `ddl-auto: validate`.

```sql
-- Organizations become tenants: one row per (organization, user), with the user's role in that organization.
-- Every request under /api/v1/organizations/{orgId}/... is checked against this table (one indexed lookup).
create table organization_memberships (
    id              bigint      not null,
    organization_id bigint      not null,
    user_account_id bigint      not null,
    role            varchar(16) not null,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    created_by      varchar(50),
    updated_by      varchar(50),

    constraint pk_organization_memberships primary key (id),
    constraint fk_organization_memberships_organization
        foreign key (organization_id) references organizations (id) on delete cascade,
    constraint fk_organization_memberships_user_account
        foreign key (user_account_id) references user_accounts (id) on delete cascade,
    -- One membership per user per organization. Its index serves the per-request lookup (organization_id, user_account_id),
    -- and it's the target later tables reference with a composite FK ("this user is a member of THIS organization").
    constraint uk_organization_memberships_org_user unique (organization_id, user_account_id),
    constraint ck_organization_memberships_role check (role in ('OWNER', 'ADMIN', 'MEMBER'))
);

-- "My organizations" looks memberships up by user; the unique index above leads with organization_id and can't serve it.
create index ix_organization_memberships_user on organization_memberships (user_account_id);

-- At most one owner per organization. A unique index is checked row by row, immediately:
-- an ownership transfer must demote the old owner BEFORE it promotes the new one.
create unique index uk_organization_memberships_one_owner
    on organization_memberships (organization_id) where role = 'OWNER';

-- At least one owner per organization, checked at COMMIT (deferred): a transaction may pass through "no owner"
-- (between the two updates of a transfer) but can never commit it.
create function organization_has_owner_check() returns trigger
    language plpgsql as $$
declare
    org_id bigint;
begin
    if tg_table_name = 'organizations' then
        org_id := new.id;
    elsif tg_op = 'DELETE' then
        org_id := old.organization_id;
    else
        org_id := new.organization_id;
    end if;

    -- A deleted organization needs no owner (its memberships cascade away with it).
    if exists (select 1 from organizations where id = org_id)
       and not exists (select 1 from organization_memberships where organization_id = org_id and role = 'OWNER') then
        -- Worded like PostgreSQL's own message, so Hibernate extracts the name and the API's 409 carries it.
        raise exception 'organization % violates check constraint "ck_organizations_has_owner"', org_id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

-- A new organization must have its owner by the end of the transaction that creates it.
create constraint trigger trg_organizations_has_owner
    after insert on organizations
    deferrable initially deferred
    for each row execute function organization_has_owner_check();

-- Changing or removing a membership must not leave its organization without an owner.
create constraint trigger trg_organization_memberships_has_owner
    after update or delete on organization_memberships
    deferrable initially deferred
    for each row execute function organization_has_owner_check();

-- Existing organizations: the creator becomes the owner where created_by names a real user (created_by has held the
-- app username since Phase 1). Organizations created anonymously ('system') stay ownerless: no request can reach them.
insert into organization_memberships (id, organization_id, user_account_id, role, created_at, updated_at, created_by, updated_by)
select nextval('global_id_seq'), o.id, u.id, 'OWNER', now(), now(), 'migration', 'migration'
from organizations o
join user_accounts u on u.username = o.created_by;
```

**Every column, constraint and index**

| Column / object | Type | What it's for | Why the constraint |
|---|---|---|---|
| `id` | `bigint` | Surrogate key from `global_id_seq` | Every entity extends `IdentifiedEntity`; the natural key is the unique constraint below |
| `organization_id` | `bigint not null` | The tenant | FK `ON DELETE CASCADE`: a deleted organization leaves no membership granting access to nothing |
| `user_account_id` | `bigint not null` | The member | FK `ON DELETE CASCADE`, consistent with sessions and events; deleting an **owner's** account is refused by the trigger at commit (*verified*) |
| `role` | `varchar(16) not null` | `OWNER` / `ADMIN` / `MEMBER` | `CHECK` on the three values; `varchar` + `@Enumerated(STRING)` like `user_accounts.role` |
| `created_at` / `created_by` | `timestamptz` / `varchar(50)` | When the member joined, and who created the row (the creator, or the invitee who accepted) | Filled by JPA auditing on insert |
| `updated_at` / `updated_by` | `timestamptz` / `varchar(50)` | Last role change, and who made it | Role changes are bulk updates (§3.2), which **bypass JPA auditing**: those queries set both columns themselves |
| `uk_organization_memberships_org_user` | unique `(organization_id, user_account_id)` | One membership per pair | Its index serves the gate's lookup; it's also the target of Phase 4's composite FKs |
| `ix_organization_memberships_user` | index `(user_account_id)` | "My organizations" | The unique index leads with `organization_id`, so it can't serve a lookup by user |
| `uk_organization_memberships_one_owner` | partial unique `(organization_id) where role = 'OWNER'` | At most one owner | Immediate, so a transfer must demote first (*verified*: promote-first fails with this name) |
| `organization_has_owner_check` + the two constraint triggers | deferred, at commit | At least one owner | A cross-row rule a `CHECK` can't express; deferred so a transfer can pass through zero owners |
| The backfill | `insert … select` | Gives existing organizations their owner | `nextval` on the shared sequence is safe alongside Hibernate's pooled ids (a fresh value is never inside a block Hibernate already holds) |

**Verified on a scratch copy of your dev database (2026-10-13)**, 14 checks: an organization committed without an owner → fails **at commit** with `ck_organizations_has_owner` (the insert itself succeeds) · organization + owner in one transaction → commits · a second `OWNER` → `uk_organization_memberships_one_owner`, immediately · a duplicate (organization, user) → `uk_organization_memberships_org_user` · role `GOD` → `ck_organization_memberships_role` · transfer promote-first → one-owner index · transfer demote-first → commits · owner membership deleted → fails at commit · owner demoted with no replacement → fails at commit · organization deleted → memberships cascade, no error · `SET CONSTRAINTS ALL IMMEDIATE` surfaces the deferred error at once · deleting a user who owns an organization → fails at commit · backfill: **2 of your 6 organizations** got an owner (`demo-org9` → alice, `demo-org98` → `abhinavvgargg-jwt`); the 4 `system` ones stay ownerless · lookups use the indexes (tiny table: the plan with real rows is owed, 📊).

#### `OrganizationRole`, `TenantContext`, `TenantErrorCode` (`common/tenant`)

- [x] `OrganizationRole` has `OWNER`, `ADMIN`, `MEMBER` and `isAbove(other)`, where OWNER is above ADMIN, which is above MEMBER.
- [x] `TenantContext` is `@RequestScope` and holds an organization id and a role.
- [x] `enter(organizationId, role)` may be called once per request; a second call throws `IllegalStateException`.
- [x] `organizationId()` and `role()` throw `IllegalStateException` when no tenant was entered (a service reached without the gate is a bug: 500).
- [x] `isEntered()` reports whether a tenant was entered (the Hibernate resolver uses it in §3.4).
- [x] `requireAtLeast(role)` throws a 403 `ORGANIZATION_ROLE_REQUIRED` when the caller's role is below it.
- [x] `TenantErrorCode` holds `ORGANIZATION_NOT_FOUND` (404) and `ORGANIZATION_ROLE_REQUIRED` (403); `OrganizationErrorCode` loses its own `ORGANIZATION_NOT_FOUND` (one code, one place).

#### `MembershipLookup` (interface, `common/tenant`) and `JpaMembershipLookup` (`organization`)

- [x] `roleOf(organizationId, userAccountId)` returns the role, or empty for a non-member or a non-existent organization.
- [x] It's one query (role only, no entity) in its own read-only transaction.

#### `TenantGateInterceptor` and `TenantWebConfig` (`common/tenant`)

- [x] The interceptor is registered for `/api/v1/organizations/*` and `/api/v1/organizations/*/**`, and not for `/api/v1/organizations` itself.
- [x] It reads the `orgId` path variable; when the variable is missing it throws `IllegalStateException` (fail closed).
- [x] A non-numeric `orgId` → 404 `ORGANIZATION_NOT_FOUND`.
- [x] It takes the user id from the `AuthenticatedUser` principal; any other principal type throws `IllegalStateException`.
- [x] No membership → 404 `ORGANIZATION_NOT_FOUND`, the same body as for an id that doesn't exist.
- [x] A membership → `TenantContext.enter(orgId, role)`.

**Done when:** alice (owner of A) gets 200 on `GET /organizations/{A}`; bob (no membership) gets a 404 on `GET /organizations/{A}` identical, bar timestamp, correlation id and path, to `GET /organizations/999999999`.

#### `Organization`, `OrganizationMembership` (entities, `organization`)

- [x] `Organization` is created through a factory `Organization.create(name, slug)`; the public no-arg constructor and setters go (nit).
- [x] `OrganizationMembership` extends `BaseEntity`; `organizationId` and `userAccountId` are `Long`s; `role` is `@Enumerated(STRING)`.
- [x] Every column of `OrganizationMembership` is `updatable = false`; it has a factory `OrganizationMembership.of(organizationId, userAccountId, role)` and no setters.

#### `OrganizationService` and `MembershipService` (`organization`)

- [x] `create(request, caller)` saves the organization and an `OWNER` membership for the caller in one transaction.
- [x] A duplicate slug is still 409 `DUPLICATE_SLUG` (checked first; `uk_organizations_slug` stays the guard).
- [x] `listMine(caller, pageable)` returns only organizations the caller is a member of, with the caller's role.
- [x] `get()` returns the organization from `TenantContext` with the caller's role.
- [x] `MembershipService.listMembers(pageable)` returns the members of the `TenantContext` organization: user id, username, display name, role, joined at; **no email** (data minimisation: members don't need each other's addresses).

#### `OrganizationController` and `MemberController`

- [x] `OrganizationController`'s `{id}` path variable is renamed `{orgId}`.
- [x] The response gains `role` (the caller's role in that organization).

| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/organizations` | `{name, slug}` | **201** `{id, name, slug, role: "OWNER", createdBy, createdAt}` + `Location` | 400 `VALIDATION_FAILED` · 401 · 409 `DUPLICATE_SLUG` |
| GET | `/api/v1/organizations?page&size&sort` | — | **200** page of `{id, name, slug, role, createdBy, createdAt}`, **mine only**; sort `name`, `createdAt` | 400 `INVALID_SORT_PROPERTY` · 401 |
| GET | `/api/v1/organizations/{orgId}` | — | **200** `{id, name, slug, role, createdBy, createdAt}` | 401 · 404 `ORGANIZATION_NOT_FOUND` (unknown **or not a member**) |
| GET | `/api/v1/organizations/{orgId}/members?page&size&sort` | — | **200** page of `{userId, username, displayName, role, joinedAt}`; sort `username` (default), `joinedAt` | 401 · 404 `ORGANIZATION_NOT_FOUND` |

**Done when:** alice creates "Acme" → 201 with `role: OWNER` and a membership row; bob's `GET /organizations` doesn't contain Acme; alice's does; `GET /organizations/{Acme}/members` → alice only.

### Traps ⚠️

- **`@TenantId` on memberships.** The gate's own lookup runs before any tenant is entered, and "my organizations" reads across tenants. Memberships are an access table: every query on it names `organization_id` (from `TenantContext`) or `user_account_id` (from the principal) in its `WHERE`.
- **A controller that names the variable `{id}` or `{organizationId}`.** The gate reads `orgId` by name; it must **fail** when it's missing, never skip. The current `OrganizationController` uses `{id}`.
- **A service that takes the org id from the path variable** instead of `TenantContext`: equal today, but the day a body field or a second path variable is used instead, the gate checked a different value from the one the query uses.
- **Deferred checks don't run in rolled-back tests.** `@DataJpaTest` rolls back each test, so a test that persists an organization **without** an owner still passes (the existing `OrganizationRepositoryTest` does exactly that). A test that means to prove the invariant flushes, then runs `SET CONSTRAINTS ALL IMMEDIATE` (*verified*).
- **The error arrives at commit, not at `save()`.** A deferred violation surfaces when the `@Transactional` method returns: Spring's `JpaTransactionManager.doCommit` translates the cause to a `DataAccessException` (*verified* in spring-orm 6.2.19); that it arrives as `DataIntegrityViolationException` with the name is *inferred* (deliberate failure 1 shows it). *(Run 2026-10-15: half right. It IS a `DataIntegrityViolationException` (Hibernate's "Unable to commit" around the raw `PSQLException`, translated by SQLState), but with no Hibernate `ConstraintViolationException` in the chain, so the 409 carried **no** constraint name. Fixed in `CommonErrorUtility`: it now reads the name from a raw `SQLException` with Hibernate's PostgreSQL extractor.)*
- **Test cleanup order**: delete organizations before users (see §3.0's traps).
- **Every web slice now loads the gate** *(verified 2026-10-15 in the `spring-boot-test-autoconfigure` 3.5.16 bytecode: `WebMvcTypeExcludeFilter` includes `HandlerInterceptor` and `WebMvcConfigurer` beans)*. `TenantGateInterceptor` and `TenantWebConfig` are pulled into `OrganizationControllerTest` and `TaskflowSecurityConfigTest`, but `TenantContext` and `JpaMembershipLookup` aren't, so **both slices stop starting** until they get a `@MockitoBean MembershipLookup` and `@Import(TenantContext.class)`. *(My brief missed this; added at §3.1's start.)*
- **`@WithMockUser` meets the gate.** Its principal is Spring's `User`, not `AuthenticatedUser`; the gate refuses any other type (500, by design). Slice tests on `/organizations/{orgId}/…` authenticate with `authentication(new TaskflowAuthenticationToken(new AuthenticatedUser(...)))` (Phase 2's tool) and stub `MembershipLookup.roleOf`.
- **`created_by` is a username, not an id**: the backfill joins on it. A user who renames themselves (no such feature) would break that join; it's a one-time migration, so fine.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| 1. In `create`, save the organization but skip the owner membership | 409 `RESOURCE_CONFLICT` with `constraint: ck_organizations_has_owner`, and no organization row (*predicted from the verified SQL and the commit path*; **observed** 2026-10-15, after the constraint-name fix below) | The database refuses what the code forgot; the error comes at commit |
| 2. Register the interceptor for `/api/v1/organizations/**` (collection included) | `GET /api/v1/organizations` → 500 (no `orgId` variable: fail closed) | Patterns decide where the gate runs; failing closed makes a wrong pattern loud |
| 3. Map one endpoint as `/organizations/{id}/members`, and make the gate skip when `orgId` is missing | bob lists alice's members: 200 | A gate that skips on a missing input is a gate with a door in it |
| 4. Return 403 for non-members | bob can tell existing organization ids from non-existent ones | Existence oracle; 404 for both |
| 5. Call `tenantContext.organizationId()` from a method run by `@Async` (temporarily add `@EnableAsync`) | `ScopeNotActiveException` in the async thread | Request scope ends at the thread boundary: Phase 9 passes ids explicitly |

### Build order

1. V7; check the backfill (`select … from organization_memberships`). 
2. `OrganizationRole`, `TenantContext`, `TenantErrorCode`, `MembershipLookup`.
3. Entities; organization creation with the owner. → *Deliberate failure 1.*
4. The gate and its registration. → *Deliberate failures 2, 3, 4.*
5. "My organizations", get, members list.
6. → *Deliberate failure 5.* Migrate the existing organization tests (the list is now "mine"; `get` needs membership): migrate, don't disable.

**Existing tests this sub-section changes** (none may be disabled):

| Test | Why it changes | What it needs |
|---|---|---|
| `TaskflowSecurityConfigTest` (web slice) | The gate's beans load in every slice | `@MockitoBean MembershipLookup`, `@Import(TenantContext.class)`; its rows stay as they are (none hit `/organizations/{orgId}`) |
| `OrganizationControllerTest` (web slice) | Same, plus `getById` now passes the gate and the service API changes | The two above; `getById` authenticates as an `AuthenticatedUser` and stubs `roleOf` → `MEMBER`; the DTO gains `role` |
| `OrganizationApiIntegrationTest` | The list is "mine", get needs membership, cleanup order | Delete organizations before users; add the non-member 404 |
| `OrganizationRepositoryTest` (JPA slice) | `Organization` gets a factory | Use `Organization.create`; it still persists without an owner, which passes only because the test rolls back (deferred check never runs): say so in a comment |
| `TaskflowSecurityIntegrationTest`, §3.0's four classes | Users who own organizations can't be deleted | Delete organizations first, or keep these classes organization-free (they are today) |

### Tests

| Kind | Must prove |
|---|---|
| Unit | `OrganizationRole.isAbove` (all 9 pairs) · `TenantContext`: second `enter` throws, read before `enter` throws, `requireAtLeast` |
| JPA slice | Owner invariant with `SET CONSTRAINTS ALL IMMEDIATE`: organization without owner fails; second owner fails immediately · `roleOf` returns empty for another organization's member |
| Web slice | The gate: missing variable → 500, non-numeric → 404, non-member → 404 (mock `MembershipLookup`) |
| Integration | Create → 201 + owner row · "mine" excludes others' organizations · non-member `GET /organizations/{A}` and `/members` → 404 identical to a random id's · member → 200 · owner deletion refused (an owner's account can't be deleted while it owns an organization: a JPA or SQL-level test) |

### Built 2026-10-15 (Claude, at your request): what differs from the brief, and the evidence

**Choices made while building** (none changes an API in the tables above):
- `OrganizationRole.isAtLeast(required)` sits next to `isAbove`; `TenantContext.requireAtLeast` uses it.
- The gate only gates **our controller methods** (a `HandlerMethod` whose bean is a `@Controller`): a path under `/organizations/{x}/` that no controller maps keeps its normal 404, and Spring's own handler methods pass. *(First built as `handler instanceof HandlerMethod`, which let through only non-`HandlerMethod` handlers; corrected 2026-10-16, see the `OPTIONS` row below.)*
- The gate's 404 detail is the constant "Organization not found", with no id property: the "identical bar timestamp, correlation id and path" rule forbids an id in the detail.
- "My organizations" default sort is `name` asc, `id` asc; its allowed sorts are `name`, `createdAt` and `id` (`PageableFactory` validates the default too, and the default names `id`). The members list maps `username` → `u.username` and `joinedAt` → `createdAt` before the query (Spring Data prefixes other names with the root alias: read in spring-data-jpa 3.5.13).
- `TestUsers.deleteAll()` deletes organizations first (one place), instead of every class staying organization-free: classes share one database, so leftovers cross classes.
- **Fix outside the brief:** `CommonErrorUtility.constraintNameOf` also reads raw `SQLException`s (deliberate failure 1 showed `constraint=null` for commit-time violations).
- **Fix after review (2026-10-16): `OPTIONS /api/v1/organizations/{id}` was a 500 with an ERROR stack trace**, for any signed-in user, repeatable at will. No data leaked (the same 500 for every id), but it was a free way to fill the error log. Cause (*verified* in spring-webmvc 6.2.19): no controller maps `OPTIONS`, so `RequestMappingInfoHandlerMapping.handleNoMatch` answers with `new HandlerMethod(new HttpOptionsHandler(...))`; path variables are stored only by `handleMatch`, which never ran, so the gate saw a `HandlerMethod` with no `orgId` and failed closed. A CORS preflight never got this far (the security chain's `CorsFilter` answers it). Fix: gate every method of a `@Controller` bean and let Spring's own handler methods through. Skipping every `OPTIONS` request was rejected because an `OPTIONS` mapping we write ourselves would then be ungated. Now: 200 with `Allow: GET,HEAD,OPTIONS`, no membership lookup, and nothing about whether the organization exists (the same answer for every id).

**Tests** (136 total, from 101; 18.6 s; still 2 Postgres containers):

| Class | Kind | What it proves |
|---|---|---|
| `common/tenant/OrganizationRoleTest` | Unit (9 rows) | `isAbove` strict and `isAtLeast` inclusive, all 9 pairs |
| `common/tenant/TenantContextTest` | Unit (5) | Enter then read; second `enter` throws and keeps the first; read before `enter` throws; `requireAtLeast` passes / 403 |
| `common/tenant/TenantGateInterceptorTest` | Web slice (6), test-only probe controller | Member → 200 with the context filled; non-member → 404 with a constant detail; non-numeric → 404 without a lookup; a `{id}` handler → 500; a non-`AuthenticatedUser` principal → 500; a plain `OPTIONS` → 200 with `Allow`, no lookup |
| `common/error/CommonErrorUtilityTest` | Unit (3) | Statement-time and commit-time violations both named; unrecognised → null |
| `organization/OrganizationMembershipRepositoryTest` | JPA slice (5) | Ownerless org fails `SET CONSTRAINTS ALL IMMEDIATE` with `ck_organizations_has_owner`; with owner it passes; second owner refused at once; duplicate membership refused; `findRole` empty for another organization's member |
| `organization/OrganizationApiIntegrationTest` | Integration (9) | Create → 201 `OWNER` + the row; duplicate slug 409; "mine" only, sorted by name; non-member `GET /{A}` and `/{A}/members` identical to `/999999999`; members = alice only, no `email`; `joinedAt` sort works, `email` sort is 400; `OPTIONS` on an organization → 200 with `Allow` through the real server; an owner's account can't be deleted |
| Migrated | | `OrganizationControllerTest` (auth as `AuthenticatedUser`, `roleOf` stubbed, non-member 404 added), `OrganizationServiceTest` (owner membership saved), `OrganizationRepositoryTest` (factory + the rollback comment), `TaskflowSecurityConfigTest` (the two gate beans) |

**Deliberate failures** (run in a scratch copy, deleted afterwards):

| # | Observed |
|---|---|
| 1 | Before the fix: 409 `RESOURCE_CONFLICT` **without** `constraint`, 0 organization rows. After: 409 with `constraint: ck_organizations_has_owner`, 0 rows |
| 2 | Gate on `/api/v1/organizations/**`: `GET` and `POST /api/v1/organizations` → **500**; two `TaskflowSecurityIntegrationTest` rows went red too |
| 3 | Gate skipping a missing `orgId`: the `{id}` probe endpoint → **200** for anyone |
| 4 | 403 for non-members: `403 FORBIDDEN` where the tests expect the 404 (slice, controller and HTTP) |
| 5 | `@Async` method reading `TenantContext`: `ScopeNotActiveException` ("Scope 'request' is not active for the current thread") on `task-1`, wrapped in a `CompletionException` → 500 |

**Mutations** (15 of 15 caught):

| # | Planted | Went red | The failing assertion |
|---|---|---|---|
| 1 | Gate skips a missing `orgId` | `aHandlerWithoutAnOrgIdVariable_failsClosed_with500` | expected 500, was **200** |
| 2 | 403 for non-members | gate, controller and integration non-member tests | expected 404, was **403** |
| 3 | "Mine" without the caller in the `WHERE` | `myOrganizations_listsOnlyTheCallersOwn_sortedByName` | got `acme, globex, zeta` |
| 4 | `create` without the owner membership | service test + every integration test that creates | "wanted but not invoked"; **409** instead of 201 |
| 5 | `enter` allows a second call | `aSecondEnter_inTheSameRequest_throws_…` | no exception raised |
| 6 | `isAbove` as `<=` | `OrganizationRoleTest` rows 1, 5, 9 (the diagonal) | expected false |
| 7 | `requireAtLeast` inverted | both `requireAtLeast` tests | 403 thrown for ADMIN ≥ ADMIN; none for ADMIN < OWNER |
| 8 | Members without the organization in the `WHERE` | `members_listsTheOrganizationsMembers_withoutEmails` | size 2, expected 1 |
| 9 | Gate on `/organizations/**` | 10 integration tests | **500** instead of 200/201 |
| 10 | Non-numeric id not mapped to 404 | `aNonNumericOrgId_isTheSame404_notA400` | expected 404, was **500** |
| 11 | `findRole` without the organization in the `WHERE` | `findRole_…AnotherOrganizationsMember` | `OWNER` instead of empty |
| 12 | The 404 detail carries the id | gate test + the whole-body comparison | "No organization 203" vs "No organization 999999999" |
| 13 | `constraintNameOf` ignores raw `SQLException`s | `aCommitTimeViolation_isNamedFromTheRawSqlException` | expected the name, was null |
| 14 | The gate as first built (`instanceof HandlerMethod` only) | `aPlainOptionsRequest_…`, `options_onAnOrganization_…` | expected 200, was **500** (`IllegalStateException: … has no {orgId} path variable`) |
| 15 | The gate recognises no controller (checks `@Service` instead) | 4 of the 6 gate tests | the mis-named endpoint **200** to a stranger; non-member 500 instead of 404 |

**Not proven yet:** the gate lookup's plan on real row counts (📊 owed, as before); your Postman run.

**Review 2026-10-16 (read only, nothing run):**
- ~~**Should fix: `OPTIONS` on any org-scoped path is a 500.**~~ **Fixed 2026-10-16** (see *Fix after review* above; re-reviewed the same day: the gate's predicate is exactly `RequestMappingHandlerMapping.isHandler`'s, and `HandlerMethod` stores the user class, so proxied controllers stay gated; both *read in 6.2.19 sources*). Original finding: Spring answers an `OPTIONS` that no controller maps with its own `HttpOptionsHandler`, wrapped in a `HandlerMethod` built in `RequestMappingInfoHandlerMapping.handleNoMatch`, which never calls `handleMatch`, so no path variables are stored (*read in spring-webmvc 6.2.19*). The gate sees a `HandlerMethod` with no `orgId` and fails closed: 500, plus an ERROR stack trace in the log. Any signed-in user can trigger it at will with a plain (non-CORS) `OPTIONS` (*predicted from the source, not run*). No data leaks (it fails closed, the same for every id). Fix: let through only Spring's own `OPTIONS` answer (the method is `OPTIONS` **and** no path variables were parsed); every other missing `orgId` stays a 500. Test: a slice `options("/api/v1/organizations/42")` → 200 with an `Allow` header, `MembershipLookup` never called; plus the existing `{id}` test still 500.
- Nits: the commented-out `findRoleByOrganizationIdAndUserAccountId` in `OrganizationMembershipRepository` (dead code) · a concurrent duplicate slug still surfaces as the generic 409 `RESOURCE_CONFLICT` (`constraint: uk_organizations_slug`) instead of `DUPLICATE_SLUG` (pre-existing; registration's `saveAndFlush` + catch pattern would map it) · `OrganizationRole`'s rule rests on declaration order: a role inserted in the wrong place silently changes who outranks whom (the 9-pair test catches it only if it's extended) · `CommonErrorUtility` builds a whole `PostgreSQLDialect` to borrow its extractor (works; ties `common/error` to the database vendor, acceptable here).
- **Re-review of the fix (2026-10-16): one test owed.** The reason this fix beats "skip every `OPTIONS`" is that an `OPTIONS` mapping *we* write stays gated, but no test pins it: change the gate to skip all `OPTIONS` and every current test stays green. Add a probe method mapped with `@RequestMapping(method = RequestMethod.OPTIONS)` under `/{orgId}/…` and assert a non-member gets the 404.
- Docs: the Phase 3 implementation reference (Definition of done) doesn't exist yet; `PROJECT_CONTEXT.md` §9 still says you implement §3.1.

### Run it (Postman; written 2026-10-16)

**0. Start and check V7** (IntelliJ, `dev` profile; Database tool window in **Auto-commit** mode)

```sql
select version, success from flyway_schema_history order by installed_rank desc limit 1;   -- 7, true
select o.slug, u.username, m.role from organization_memberships m
  join organizations o on o.id = m.organization_id join user_accounts u on u.id = m.user_account_id;
-- expected: demo-org9 | alice | OWNER  and  demo-org98 | abhinavvgargg-jwt | OWNER  (the 4 'system' orgs: no row)
```

**1. Postman setup**: one collection, *TaskFlow 3.1*. Collection variables: `base` = `http://localhost:8080`, `password` = `p31-password-123`, and empty `aliceToken`, `bobToken`, `carolToken`, `acmeId`, `globexId`, `notMemberBody`. Every org request sets `Authorization: Bearer {{…Token}}` (Authorization tab → Bearer Token). The cookie jar doesn't matter here (no refresh in §3.1).

**2. Users** (once). `POST {{base}}/api/v1/auth/register` three times, body `{"email":"p31alice@example.com","username":"p31alice","displayName":"P31 Alice","password":"{{password}}"}` (then `p31bob`, `p31carol`) → **201** each. Verify them by SQL instead of copying three links from the console:

```sql
update user_accounts set email_verified_at = now() where username in ('p31alice','p31bob','p31carol');   -- 3 rows
```

`POST {{base}}/api/v1/auth/login` `{"email":"p31alice@example.com","password":"{{password}}"}` → **200**; Post-response script: `pm.collectionVariables.set("aliceToken", pm.response.json().accessToken);` Same for bob (`bobToken`) and carol (`carolToken`). Tokens live 15 minutes: re-send the three logins if anything turns 401 `INVALID_ACCESS_TOKEN`.

**3. The run.** Watch the IntelliJ console: every request under `/organizations/{id}` logs one `select … role … from organization_memberships` (the gate) before anything else.

| # | As | Request | Expected | Proves |
|---|---|---|---|---|
| 1 | alice | `POST /api/v1/organizations` `{"name":"P31 Acme","slug":"p31-acme"}`; script: `pm.collectionVariables.set("acmeId", pm.response.json().id);` | **201**, `role: "OWNER"`, `createdBy: "p31alice"`, `Location: …/organizations/{id}` | Creating makes you the owner |
| 2 | alice | same request again | **409** `DUPLICATE_SLUG`, `slug: "p31-acme"` | Unchanged slug rule |
| 3 | bob | `POST /api/v1/organizations` `{"name":"P31 Globex","slug":"p31-globex"}`; save `globexId` | **201** `OWNER` | |
| 4 | alice | `GET /api/v1/organizations` | **200**, `content` = only `p31-acme`, `role: "OWNER"` | "Mine" only (it used to list all) |
| 5 | bob | `GET /api/v1/organizations` | only `p31-globex` | |
| 6 | alice | `GET /api/v1/organizations/{{acmeId}}` | **200**, `role: "OWNER"` | Member passes the gate |
| 7 | bob | `GET /api/v1/organizations/{{acmeId}}`; script (strip and keep the body): `const b = pm.response.json(); ["timestamp","correlationId","instance"].forEach(k => delete b[k]); pm.collectionVariables.set("notMemberBody", JSON.stringify(b));` | **404** `ORGANIZATION_NOT_FOUND`, `detail: "Organization not found"` | Non-member = not found |
| 8 | bob | `GET /api/v1/organizations/999999999`; script: same strip, then `pm.test("same as not-a-member", () => pm.expect(JSON.stringify(b)).to.eql(pm.collectionVariables.get("notMemberBody")));` | **404**, test passes | No existence oracle |
| 9 | bob | `GET /api/v1/organizations/abc` (same test script) | **404**, test passes (not a 400) | Non-numeric is the same miss |
| 10 | alice | `GET /api/v1/organizations/{{acmeId}}/members` | **200**, one member `p31alice` `OWNER`, `joinedAt`, **no `email`** | Members list |
| 11 | bob | `GET /api/v1/organizations/{{acmeId}}/members` | **404** `ORGANIZATION_NOT_FOUND` | Gate covers sub-paths |
| 12 | — | SQL: add carol as a MEMBER (below) | 1 row | No invitations until §3.3 |
| 13 | carol | `GET /api/v1/organizations` and `GET /api/v1/organizations/{{acmeId}}` | acme with `role: "MEMBER"`; **200** `MEMBER` | Role comes from the membership |
| 14 | alice | `GET …/{{acmeId}}/members` then `?sort=joinedAt,desc` | `p31alice, p31carol` (username asc); then `p31carol` first | Default and joined sort |
| 15 | alice | `GET …/{{acmeId}}/members?sort=email` · `GET /api/v1/organizations?sort=slug` | **400** `INVALID_SORT_PROPERTY` both | Sort allow-lists |
| 16 | — | SQL: remove carol (below), then carol repeats `GET /api/v1/organizations/{{acmeId}}` **with the same token** | **404** | Membership read per request (D3): removal acts on the next request, no new token needed |
| 17 | alice, bob | `OPTIONS {{base}}/api/v1/organizations/{{acmeId}}` (no `Origin` header) | **200** both, `Allow: GET,HEAD,OPTIONS`, empty body, no gate query in the log | The review fix; same answer for a non-member |
| 18 | nobody | `GET /api/v1/organizations/{{acmeId}}` with Auth = *No Auth* | **401** `AUTHENTICATION_REQUIRED` | Security chain before the gate |
| 19 | alice | `GET /api/v1/organizations/602` (a `system`-created legacy org) | **404** | Ownerless legacy orgs are unreachable |

```sql
-- 12: carol joins acme as a MEMBER (replace 123 with {{acmeId}})
insert into organization_memberships (id, organization_id, user_account_id, role, created_at, updated_at, created_by, updated_by)
select nextval('global_id_seq'), 123, id, 'MEMBER', now(), now(), 'manual', 'manual' from user_accounts where username = 'p31carol';
-- 16: carol leaves (by hand, until §3.2's endpoint exists)
delete from organization_memberships where organization_id = 123 and user_account_id = (select id from user_accounts where username = 'p31carol');
```

**4. The database invariants** (Auto-commit on, so each statement commits and the deferred trigger runs at its end; replace 123):

| # | SQL | Expected |
|---|---|---|
| 20 | `delete from organization_memberships where organization_id = 123 and role = 'OWNER';` | `ERROR: organization 123 violates check constraint "ck_organizations_has_owner"`; the row is still there |
| 21 | `insert into organization_memberships (id, organization_id, user_account_id, role, created_at, updated_at) select nextval('global_id_seq'), 123, id, 'OWNER', now(), now() from user_accounts where username = 'p31bob';` | `duplicate key … "uk_organization_memberships_one_owner"` |
| 22 | `delete from user_accounts where username = 'p31alice';` | `ck_organizations_has_owner` (the cascade fired the trigger); alice still exists |
| 23 | `update organization_memberships set role = 'GOD' where organization_id = 123;` | `ck_organization_memberships_role` |

If the console is in **manual-commit** mode, 20 and 22 succeed at the statement and fail at **Commit**: that's the deferred trigger, and seeing it once is worth it.

**5. Report back**: rows that differed (status and body), and whether the console showed one gate query per org-scoped request.

✅ **Reported 2026-10-16: all 23 rows passed as expected** (the console's one-gate-query check wasn't reported separately). Dev DB now at **V7**.

**Clean up afterwards (optional):** `delete from organizations where slug in ('p31-acme','p31-globex');` then `delete from user_accounts where username like 'p31%';` (organizations first, or 22's error comes back).

---

## 3.2 — Managing members: roles, removal, leaving, ownership transfer

### What we're building

The owner can promote a member to admin and demote an admin to member. Owners remove admins and members; admins remove members. Anyone but the owner can leave. The owner can hand ownership to another member (with their current password), becoming an admin. Every change takes effect on the affected user's next request, because the gate reads the membership each time.

- **In:** `PATCH` and `DELETE` on members, leaving, ownership transfer, the re-authentication it needs.
- **Out:** suspending a member, member-level audit history (Phase 7's audit log), notifying the affected user (Phase 9).

### How we're building it, and why

**The rule** (D6): you act only on members **strictly below** you, and grant only roles **strictly below** yours. Everything in the matrix follows from it; the code checks the rule, not a list of cases.

**Every change is one conditional statement whose `WHERE` carries what the decision assumed** (Phase 2 rule A6):

| Operation | Statement (shape) | 0 rows means |
|---|---|---|
| Change role | `UPDATE … SET role = :new, updated_at, updated_by WHERE organization_id = :org AND user_account_id = :target AND role = :expectedCurrent` | the member is gone or their role changed since you read it → 409 `MEMBERSHIP_CHANGED` |
| Remove | `DELETE … WHERE organization_id = :org AND user_account_id = :target AND role = :expectedCurrent` | same → 409 |
| Leave | `DELETE … WHERE organization_id = :org AND user_account_id = :me AND role <> 'OWNER'` | owner → 409 `OWNER_CANNOT_LEAVE` |
| Transfer, step 1 | `UPDATE … SET role = 'ADMIN' … WHERE organization_id = :org AND user_account_id = :me AND role = 'OWNER'` | you're no longer the owner (a concurrent transfer) → 409 |
| Transfer, step 2 | `UPDATE … SET role = 'OWNER' … WHERE organization_id = :org AND user_account_id = :target AND role <> 'OWNER'` | target isn't a member → 404 `MEMBER_NOT_FOUND`, the whole transaction rolls back |

The service first **reads** the target's current role (404 if not a member: the caller is inside the organization, so it may know who's a member), applies the rule (403 if the target or the new role isn't strictly below the caller), then runs the conditional statement with the role it read.

**Ownership transfer** (`POST /organizations/{orgId}/ownership-transfer {userId, currentPassword}`): owner only; re-authenticates the caller with their current password (Phase 1's path: email loaded by id, the `AuthenticationManager`, a wrong password counts toward lockout); then step 1 and step 2 in one transaction, **in that order**: the one-owner index is immediate (*verified*: promote-first fails), the at-least-one trigger is deferred (*verified*: the zero-owner moment between them is allowed).

#### The choices

| Choice | Problem it solves | What it avoids |
|---|---|---|
| **"Strictly below" as one rule in `OrganizationRole`** | Every case in the matrix comes from one comparison | A matrix of `if`s that disagrees with itself (admins able to remove admins in one endpoint but not another) |
| **Conditional statements with the expected role in the `WHERE`** | A role change and a transfer racing: exactly one applies, the other gets 409 | Lost updates: the loser's write turning the new owner back into a member (the trigger would then refuse the commit: a 409 that explains nothing) |
| **The queries set `updated_at` and `updated_by` themselves** | Audit columns stay true | JPA auditing runs only on entity flushes, never on `@Modifying` queries: the columns would keep their insert values |
| **Transfer demotes first, then promotes** | Satisfies the immediate one-owner index | 409 on every transfer |
| **Transfer requires the current password** | A stolen 15-minute access token can't hand the organization to the attacker | "Session hijack = permanent org takeover"; Phase 2 rule A1: judge by the attacker's copy |
| **Re-authentication extracted once in `user` and reused** by change-password and transfer | One implementation that counts toward lockout | Two re-auth paths, one of which forgets the lockout listener |
| **Leave is its own endpoint (`/members/me`)** | "Strictly below" forbids acting on yourself; leaving is the one self-action | Special-casing "target == caller" inside remove |
| **Removed member: 404 on their next org request** | The gate reads memberships per request | A removed admin still acting for 15 minutes |

#### Alternatives we didn't take

| Alternative | Why not | The concrete problem it would cause later |
|---|---|---|
| Load the membership entity, `setRole`, let dirty checking flush | Simple | Hibernate flushes UPDATEs in **its** order, not the order you called the setters, so "demote then promote" isn't guaranteed (*inferred*: flush order follows the persistence context, not your code); and a stale entity overwrites a concurrent change |
| `@Version` on memberships | Optimistic locking would catch the race | Phase 5 teaches `@Version`; here the expected role in the `WHERE` is the version, at zero cost |
| Transfer without the password | Fewer steps | An attacker with a stolen access token (XSS, a shared machine) transfers the organization to themselves in one request; the owner can't undo it |
| Admins can change roles | Convenience | Breaks "strictly below": an admin could promote a member to admin (a peer) |
| Owner can leave, ownership passes to the oldest admin | No dead end | A silent, unchosen owner; an organization with no admins left ownerless anyway |

### What we're optimising for

**No escalation by construction**, **every race decided by the database**, and **an irreversible action that a stolen access token can't perform**.

### Concepts

💡 **Bulk updates bypass the persistence context and its listeners.** A `@Modifying` query goes straight to SQL: no dirty checking, no `@PreUpdate`, no JPA auditing (Phase 2 learned the first half with `updatable = false`; this is the second).
💡 **Sudo mode.** High-impact, hard-to-reverse actions ask for the password again even inside a valid session (GitHub calls it sudo mode). The access token proves "this browser was logged in recently"; the password proves "the owner is at the keyboard now".

### Requirements

#### Re-authentication (`user`)

- [ ] One method re-authenticates an account id with a password through the `AuthenticationManager` (email loaded by id) and is used by change-password and by transfer.
- [ ] A wrong password → 400 `CURRENT_PASSWORD_INCORRECT` with `field: currentPassword`, counted toward lockout (Phase 1's change-password behaviour, unchanged).

#### `MembershipService` (`organization`)

- [ ] `changeRole(targetUserId, newRole)`: OWNER only in practice (the rule); target and new role strictly below the caller; else 403 `CANNOT_MANAGE_MEMBER`.
- [ ] `remove(targetUserId)`: target strictly below the caller; else 403 `CANNOT_MANAGE_MEMBER`.
- [ ] `leave()`: the owner gets 409 `OWNER_CANNOT_LEAVE`.
- [ ] `transferOwnership(targetUserId, currentPassword)`: caller must be OWNER (403 otherwise); password re-checked; demote, then promote, in one transaction.
- [ ] A target who isn't a member → 404 `MEMBER_NOT_FOUND` (the target's id from another organization is "not a member": same 404).
- [ ] Each statement's 0-row result maps to the error in the table above; none loads an entity to change it.
- [ ] Each statement sets `updated_at` (the injected `Clock`) and `updated_by` (the caller's username).

**Done when:** after alice transfers Acme to carol, alice is ADMIN and carol is OWNER; alice's next `POST /ownership-transfer` → 403.

#### `MemberController`

| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| PATCH | `/api/v1/organizations/{orgId}/members/{userId}` | `{role: "ADMIN" \| "MEMBER"}` | **200** `{userId, username, displayName, role, joinedAt}` | 400 `VALIDATION_FAILED` (incl. `OWNER`) · 403 `CANNOT_MANAGE_MEMBER` · 404 `ORGANIZATION_NOT_FOUND` / `MEMBER_NOT_FOUND` · 409 `MEMBERSHIP_CHANGED` |
| DELETE | `/api/v1/organizations/{orgId}/members/{userId}` | — | **204** | 403 `CANNOT_MANAGE_MEMBER` (incl. yourself: use `/members/me`) · 404 · 409 `MEMBERSHIP_CHANGED` |
| DELETE | `/api/v1/organizations/{orgId}/members/me` | — | **204** | 404 `ORGANIZATION_NOT_FOUND` · 409 `OWNER_CANNOT_LEAVE` |
| POST | `/api/v1/organizations/{orgId}/ownership-transfer` | `{userId, currentPassword}` | **204** | 400 `VALIDATION_FAILED` (incl. yourself as target) / `CURRENT_PASSWORD_INCORRECT` · 403 `ORGANIZATION_ROLE_REQUIRED` · 404 `MEMBER_NOT_FOUND` · 409 `MEMBERSHIP_CHANGED` |

**Done when:** every row of D6's matrix gives its status for OWNER, ADMIN and MEMBER callers; a removed member's next `GET /organizations/{A}` → 404.

### Traps ⚠️

- **Promote before demote** → 409 `uk_organization_memberships_one_owner` on every transfer (*verified* at SQL level).
- **`DELETE …/members/me` vs `…/members/{userId}`**: Spring prefers the literal segment over the variable (path-pattern specificity), so `me` never reaches `{userId}`; but a `Long` `userId` receiving `me` would be a 400, so keep the literal mapping and test it.
- **The password in the transfer request body**: mask it in the request record's `toString()` (Phase 1: request records print every component).
- **Reading the role, then deciding, then writing** is check-then-act *unless* the write repeats the read value in its `WHERE`. Without `AND role = :expectedCurrent`, the race is back.
- **A transfer to yourself** looks like a success: step 1 demotes you to `ADMIN`, then step 2's `role <> 'OWNER'` matches **you** and promotes you back, so it returns 204 having done nothing. Reject `targetUserId == caller` with 400 before anything runs.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| 1. Transfer: promote first, then demote | 409 `RESOURCE_CONFLICT`, `constraint: uk_organization_memberships_one_owner` | An immediate unique index orders your statements |
| 2. Remove the step-2 row check (ignore 0 rows) and transfer to a non-member | 409 `ck_organizations_has_owner` at commit (the owner was demoted, nobody promoted) | The deferred trigger catches what the code didn't check; but the right error is 404, so check the count |
| 3. Drop `AND role = :expectedCurrent` from change-role; in two terminals, transfer to carol and demote carol to MEMBER at once | Sometimes a 409 at commit with no clear cause, sometimes a lost change | Check-then-act; the expected value belongs in the `WHERE` |
| 4. Let `changeRole` use "at or below" | An admin promotes a member to admin | Escalation; strictly below |
| 5. Change a role with a `@Modifying` query that doesn't set `updated_by` | `updated_by` still says who created the membership | Bulk updates bypass JPA auditing |

### Build order

1. Extract re-authentication; change-password still works (§3.0's tests 7 prove it, if D1 = A).
2. Change role and remove. → *Deliberate failures 3, 4, 5.*
3. Leave.
4. Transfer. → *Deliberate failures 1, 2.*

### Tests

| Kind | Must prove |
|---|---|
| Unit | The rule: every (actor, target, new role) combination of the matrix |
| Integration | The matrix for three callers · removed member → 404 next request · transfer: roles swapped, old owner 403 on a second transfer, wrong password 400 and nothing changed, yourself as target 400 · transfer to a non-member → 404, nothing changed · owner leave → 409 · `/members/me` doesn't reach `{userId}` · a member of B as target in A → 404 |
| Integration (parallel) | Two transfers by the same owner to different members at once → exactly one 204, one 409; exactly one owner afterwards |

---

## 3.3 — Invitations

### What we're building

An owner or admin invites someone **by email** with a role (MEMBER, or ADMIN if the inviter is the owner). The invitation is open for 7 days. The invitee, once signed in with that email, sees it under "my invitations" and accepts (becoming a member with that role) or declines. Admins see and revoke open invitations. Re-inviting the same email replaces the open invitation. No email is sent yet (deferred); there is no token (D7).

- **In:** V8, inviting, listing and revoking (organization side), listing, accepting and declining (invitee side), expiry.
- **Out:** sending invitation emails (`PROJECT_CONTEXT.md` §6, deferred), auto-joining at registration (the invitee accepts explicitly), the cleanup of old invitations (Phase 9's job), invitation links (D7).

### How we're building it, and why

**Two sides, two kinds of key** (the IDOR rule, twice):

| Side | Path | The `WHERE` always contains |
|---|---|---|
| Organization | `/api/v1/organizations/{orgId}/invitations…` (through the gate) | `organization_id = <TenantContext>` |
| Invitee | `/api/v1/users/me/invitations…` (no gate: no tenant yet) | `email = <the caller's current email, loaded by id>` |

**Accept** is one transaction: a conditional `UPDATE … SET accepted_at WHERE id = :id AND email = :myEmail AND <open> AND expires_at > :now` (1 row, or 404) → insert the membership with the invitation's role. If the caller is already a member, the insert fails on `uk_organization_memberships_org_user` and the whole transaction rolls back (the invitation stays open; the caller can decline it). **Invite** is one transaction too: refuse an email that already belongs to a member (409), revoke the open invitation for (organization, email) if any, insert the new one.

#### The choices

| Choice | Problem it solves | What it avoids |
|---|---|---|
| **No token; the verified email is the key** ↺ D7 | A forwarded invitation can't be used by anyone else | Join links in logs and chats; a second secret to store and expire |
| **The invitee's email in the accept/decline `WHERE`**, loaded by the caller's id | Someone else's invitation id matches no row → 404 | Load-then-check that a refactor forgets (Phase 2's IDOR lesson) |
| **Partial unique index: one open invitation per (organization, email)** | Two concurrent invites can't both be open | Two open invitations with different roles: which one does accept apply? |
| **Re-invite revokes the open one, then inserts** | Expired-but-open invitations would otherwise block re-inviting forever (expiry can't be in an index predicate: `now()` isn't immutable) | A permanently stuck email; the same pattern as Phase 1's "revoke on reissue" |
| **`role` can't be `OWNER`** (a `CHECK` and request validation) and must be strictly below the inviter ↺ D6 | Ownership moves only by transfer; admins can't invite admins | An invitation as a back door to escalation |
| **Emails normalised (trim + lowercase) with Phase 1's `Normalize`**, and the same `CHECK`s as `user_accounts.email` | "Invitation email = account email" compares like with like | `Bob@Example.com` invited, `bob@example.com` signs in, never sees it |
| **Expired invitations aren't listed, and accepting one → 404** | One answer for "can't be accepted" | A distinct "expired" response that tells a guesser the id exists |
| **Accept returns the new membership** `{organizationId, role}` | The client can navigate straight into the organization | A second request to find out where you landed |
| **The already-member check before the insert**, the unique constraint as the guard | A clear 409 `ALREADY_MEMBER`; the race still can't create a duplicate | Phase 0's three layers: validation, service rule, database constraint |

#### Alternatives we didn't take

| Alternative | Why not | The concrete problem it would cause later |
|---|---|---|
| A token link ↺ D7-B/C | See D7 | A forwarded email lets a stranger in (C), or a second secret for no gain (B) |
| Auto-accept at registration or login when an invitation matches | Fewer clicks | Someone joins an organization they never chose (an attacker invites a victim's address into an org they control, and the victim's next login lands them in it, with their name visible to the attacker) |
| Store the invitee's **user id** instead of the email | Simpler join | Invitations to people without accounts would be impossible; and an account's email change (backlog) wouldn't move its invitations |
| `status` enum column instead of three timestamps | One column | Loses *when* each outcome happened; the single-outcome `CHECK` already gives one state |
| Invitation id in the accept body instead of the path | — | The path is the resource; consistent with `/users/me/sessions/{id}` |
| `@TenantId` on invitations ↺ D8-C | Auto-scoping | The invitee side reads across organizations and would need a root session |

### What we're optimising for

**An invitation only ever works for the address it was sent to**, **one open invitation per address and organization, decided by the database**, and **no enumeration from the invitee side**.

### Concepts

💡 **Two-sided resources.** Some rows are read by two different owners: the organization (admins) and the person invited. Each side gets its own path and its own key in the `WHERE`. Neither side uses the other's.
💡 **Why `now()` can't be in a partial index.** An index predicate must be immutable: the set of rows it covers can't change while nobody writes. "Not expired" changes with the clock, so expiry is checked in queries, and the index only knows "not accepted, declined or revoked".

### Requirements

#### The migration (V8): you apply it; the SQL is below (assumes D7 = A)

- [ ] `V8__create_organization_invitations.sql` is added exactly as given.

```sql
-- An invitation to join an organization, addressed to an email address (the invitee may not have an account yet).
-- It's accepted by the signed-in user whose verified email matches; there is no token (decision D7).
create table organization_invitations (
    id              bigint       not null,
    organization_id bigint       not null,
    email           varchar(254) not null,
    role            varchar(16)  not null,
    expires_at      timestamptz  not null,
    accepted_at     timestamptz,
    declined_at     timestamptz,
    revoked_at      timestamptz,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    created_by      varchar(50),
    updated_by      varchar(50),

    constraint pk_organization_invitations primary key (id),
    constraint fk_organization_invitations_organization
        foreign key (organization_id) references organizations (id) on delete cascade,
    -- Never OWNER: ownership only moves by transfer.
    constraint ck_organization_invitations_role check (role in ('ADMIN', 'MEMBER')),
    -- Same rules as user_accounts.email, so "invitation email = account email" compares like with like.
    constraint ck_organization_invitations_email_lowercase check (email = lower(email)),
    constraint ck_organization_invitations_email_format check (email ~ '^[^@[:space:]]+@[^@[:space:]]+$'),
    constraint ck_organization_invitations_expiry check (expires_at > created_at),
    -- An invitation ends at most one way: accepted, declined or revoked.
    constraint ck_organization_invitations_single_outcome
        check (num_nonnulls(accepted_at, declined_at, revoked_at) <= 1)
);

-- At most one open invitation per (organization, email). Expiry can't be part of the predicate (now() isn't
-- immutable), so an expired-but-open invitation still holds the slot: re-inviting revokes it first.
create unique index uk_organization_invitations_open
    on organization_invitations (organization_id, email)
    where accepted_at is null and declined_at is null and revoked_at is null;

-- "My invitations": open invitations addressed to the caller's email, across organizations.
create index ix_organization_invitations_open_email
    on organization_invitations (email)
    where accepted_at is null and declined_at is null and revoked_at is null;
```

| Column / object | Type | What it's for | Why the constraint |
|---|---|---|---|
| `id` | `bigint` | Key; the invitee accepts by it | PK; guessable, so every use has the email or the organization in its `WHERE` |
| `organization_id` | `bigint not null` | Which organization | FK `ON DELETE CASCADE`: a deleted organization's invitations go with it |
| `email` | `varchar(254) not null` | Who is invited | Lowercase + format `CHECK`s identical to `user_accounts.email` (254: the RFC 5321 maximum) |
| `role` | `varchar(16) not null` | The role granted on accept | `CHECK` in `ADMIN`, `MEMBER`: never `OWNER` |
| `expires_at` | `timestamptz not null` | Creation + 7 days (configurable) | `CHECK expires_at > created_at` |
| `accepted_at` / `declined_at` / `revoked_at` | `timestamptz`, nullable | The outcome and when | `num_nonnulls(...) <= 1`: at most one outcome |
| `created_at` / `created_by` | audit | When invited, and **by whom** (the inviter's username: shown to the invitee) | JPA auditing on insert |
| `updated_at` / `updated_by` | audit | When answered or revoked, and by whom | Set by the conditional updates themselves (they bypass auditing) |
| `uk_organization_invitations_open` | partial unique | One open invitation per (organization, email) | Decides concurrent invites in the database |
| `ix_organization_invitations_open_email` | partial index | "My invitations" | Lookups by email across organizations; only open rows are indexed |

**Verified on the scratch copy (2026-10-13):** a second open invitation for the same (organization, email) → `uk_organization_invitations_open` · revoke-then-insert in one transaction → commits · role `OWNER`, an uppercase email, `expires_at = created_at`, two outcomes → each refused by its named `CHECK` · accept as a conditional `UPDATE`: wrong email → **0 rows**, right email → **1**, again → **0** · organization deleted → invitations cascade · "my invitations" uses the partial index.

#### Configuration

- [ ] `taskflow.organizations.invitation-ttl` (typed, positive, `7d` in `application.yml`), validated at startup.

#### `OrganizationInvitation` (entity, `organization.invitation`)

- [ ] Extends `BaseEntity`; insert-only from Java (every column `updatable = false`, a factory, no setters); `organizationId` is a `Long`.
- [ ] The factory takes an already-normalised email, the role and `expires_at` computed from one clock reading.

#### `InvitationService` (`organization.invitation`)

- [ ] `invite(email, role)`: requires ADMIN or above; the role must be strictly below the caller's (403 `ROLE_NOT_GRANTABLE`); an email that belongs to a member → 409 `ALREADY_MEMBER`.
- [ ] `invite` revokes the open invitation for (organization, email), then inserts the new one, in one transaction.
- [ ] `listOpen(pageable)` returns the organization's open, unexpired invitations (ADMIN or above).
- [ ] `revoke(invitationId)`: a conditional update with the organization in the `WHERE`; 0 rows → 404 `INVITATION_NOT_FOUND`.
- [ ] `listMine(caller, pageable)` returns open, unexpired invitations addressed to the caller's current email, with the organization's name.
- [ ] `accept(caller, invitationId)`: the conditional update (email and expiry in the `WHERE`), then the membership insert, in one transaction; 0 rows → 404 `INVITATION_NOT_FOUND`; already a member → 409 `ALREADY_MEMBER`.
- [ ] `decline(caller, invitationId)`: a conditional update; 0 rows → 404.
- [ ] Every conditional update sets `updated_at` and `updated_by`.

**Done when:** alice invites `bob@…` as MEMBER; bob lists it, accepts → 200 `{organizationId, role: MEMBER}`; bob's `GET /organizations/{Acme}` → 200; accepting again → 404.

#### `InvitationController` (organization side) and `MyInvitationController` (invitee side)

| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/organizations/{orgId}/invitations` | `{email, role: "ADMIN" \| "MEMBER"}` | **201** `{id, email, role, invitedBy, createdAt, expiresAt}` + `Location` | 400 `VALIDATION_FAILED` · 403 `ORGANIZATION_ROLE_REQUIRED` / `ROLE_NOT_GRANTABLE` · 404 · 409 `ALREADY_MEMBER` |
| GET | `/api/v1/organizations/{orgId}/invitations?page&size` | — | **200** page of the above, open and unexpired, newest first | 403 · 404 |
| DELETE | `/api/v1/organizations/{orgId}/invitations/{invitationId}` | — | **204** | 403 · 404 `INVITATION_NOT_FOUND` (unknown, another organization's, or no longer open) |
| GET | `/api/v1/users/me/invitations?page&size` | — | **200** page of `{id, organizationId, organizationName, role, invitedBy, expiresAt}` | 401 |
| POST | `/api/v1/users/me/invitations/{invitationId}/accept` | — | **200** `{organizationId, role}` | 401 · 404 `INVITATION_NOT_FOUND` (unknown, not yours, expired, answered, revoked) · 409 `ALREADY_MEMBER` |
| POST | `/api/v1/users/me/invitations/{invitationId}/decline` | — | **204** | 401 · 404 `INVITATION_NOT_FOUND` |

**Done when:** carol tries to accept bob's invitation id → 404 identical to a random id's, and bob's invitation is still open.

### Traps ⚠️

- **Comparing un-normalised emails.** Normalise the invited address with `Normalize.normalizeEmail` before storing; the `CHECK` refuses uppercase as the backstop.
- **The bearer principal has no email** (Phase 2: no PII in tokens): load it by id (`findEmailById`) for "my invitations", accept and decline.
- **Expiry in the index predicate** is impossible (`now()` isn't immutable): that's why re-invite revokes first, and why every read filters `expires_at > now`.
- **Accept's two steps must be one transaction**: an accepted invitation with no membership (or the reverse) is a state nobody can fix from the API.
- **The "already a member" check isn't enough alone**: two accepts racing for two different invitations of the same person can't happen (one open invitation per email and organization), but the unique constraint stays the guard.
- **`/users/me/invitations` must not go through the gate**: it's under `/users`, not `/organizations`, on purpose (no tenant exists until you accept).

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| 1. Accept by id only (no email in the `WHERE`); carol accepts bob's invitation | 200: carol joined with bob's role | IDOR; the owner's key belongs in the `WHERE` |
| 2. Store the email as typed; invite `Bob@Example.com` | Insert refused by `ck_organization_invitations_email_lowercase` (or, without the `CHECK`, bob never sees it) | Normalise at the boundary; the database backs it up |
| 3. Re-invite without revoking first | 409 `uk_organization_invitations_open` | The open slot is held until the old invitation is closed |
| 4. Fire 2 accepts of the same invitation at once (terminal) | One 200, one 404; one membership | The conditional `UPDATE` decides, as in §2.3 |
| 5. Let an admin invite an ADMIN | An admin creates a peer | Strictly below applies to invitations too |

### Build order

1. V8, configuration, entity.
2. Invite (organization side), list, revoke. → *Deliberate failures 2, 3, 5.*
3. My invitations, accept, decline. → *Deliberate failures 1, 4.*

### Tests

| Kind | Must prove |
|---|---|
| JPA slice | Conditional accept: wrong email 0, right 1, again 0, expired 0 (clock) · one open invitation per (organization, email) |
| Integration | Invite → list mine → accept → member · accept someone else's → 404 identical to a random id's · expired → not listed, accept 404 (clock) · re-invite replaces · already member → 409 · admin invites ADMIN → 403 · member invites → 403 · revoke → can't accept · organization side of B with A's invitation id → 404 |
| Integration (parallel) | 10 accepts of one invitation → exactly one 200, one membership |

---

## 3.4 — Tenant-owned data: projects and Hibernate's tenant filter

### What we're building

Organizations get **projects** (create, list, get), each with a key unique **within** its organization (`TF` can exist in two organizations). Projects are the first **tenant-owned** entity: `Project.organizationId` is a Hibernate `@TenantId`, so every query on projects (including `findById`) is automatically restricted to the organization the gate entered, and new projects get their organization from the session. A member of A who sends B's project id gets a 404, even though the code calls a plain `findById`.

- **In:** V9, the `Project` entity with `@TenantId`, the tenant-identifier resolver and its "no tenant" value, its registration, the project endpoints, the cross-tenant tests.
- **Out (Phase 4):** project membership and project roles, project settings, archive/restore, the permission evaluator. **Out (Phase 9):** running tenant-scoped work outside a request (async, scheduled), which needs an explicit tenant scope.

### How we're building it, and why

**The moving parts**

1. **V9**: `projects` with `organization_id`, `project_key` unique per organization.
2. **`Project`** (entity, `project`): `@TenantId Long organizationId` (never set by our code: Hibernate fills it from the session), `projectKey`, `name`, `BaseEntity` audit.
3. **`TenantIdentifierResolver`** (`common/tenant`, implements `CurrentTenantIdentifierResolver<Long>`): returns the entered organization id; returns **`NO_TENANT` (0)** when there's no active request or the gate didn't enter one (login, refresh, "my organizations", `@Scheduled` jobs, slice tests). `isRoot` is never true: no session can see all tenants.
4. **`TenantHibernateConfig`**: a `HibernatePropertiesCustomizer` that puts the resolver under `hibernate.tenant_identifier_resolver` (*verified*: `MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER`; Boot 3.5.16 ships `HibernatePropertiesCustomizer`).
5. **`ProjectService`** / **`ProjectController`** under `/api/v1/organizations/{orgId}/projects`.

**What happens on `GET /organizations/{A}/projects/{id}`**

```
gate: alice is a member of A → TenantContext.enter(A, OWNER)
ProjectService.get(id)  @Transactional(readOnly) → a new Hibernate session opens
  → resolver: request active, tenant entered → A        (asked once, at session open: verified)
  → session enables the _tenantId filter with A          (verified: setUpMultitenancy)
projectRepository.findById(id)
  → select … from projects where id = ? and organization_id = A     (applyToLoadByKey = true: verified)
  → B's project: no row → Optional.empty → 404 PROJECT_NOT_FOUND
```

And on `POST /api/v1/auth/login`: the resolver sees no entered tenant → `NO_TENANT`; every session still gets a value (so Hibernate doesn't throw), and any accidental project query in a non-tenant request matches nothing (`organization_id = 0`): **fail closed**.

#### The choices

| Choice | Problem it solves | What it avoids |
|---|---|---|
| **`@TenantId`, not a hand-made `@Filter`** ↺ D4 | `findById`, to-one fetches, every HQL/criteria query (incl. Phase 8's Specifications) and bulk HQL are scoped; inserts get the organization; an insert for another organization is refused (*all verified in source*) | A filter that misses `findById` and has to be enabled per transaction |
| **`NO_TENANT = 0` for sessions without a tenant**, not `null` and not root | Hibernate requires a value for every session (*verified*); `0` is never an organization id (the sequence starts at 1) | `null` → every session in the app fails (login included); root → every non-tenant request sees every tenant's projects (**fail open**) |
| **The resolver checks for an active request before touching `TenantContext`** | Scheduled jobs and slice tests open sessions too | `ScopeNotActiveException` at every session opened outside a request |
| **`isRoot` always false in Phase 3** | No code path sees all tenants | A convenience bypass that becomes the hole |
| **Never set `organizationId` in code**; Hibernate fills it at insert | The tenant comes from the session, which came from the gate | A body field or path variable for another organization: Hibernate refuses a mismatch with `PropertyValueException` (*verified*), a 500 that's really a bug report |
| **Key unique per organization**: `unique (organization_id, project_key)` | Tenants don't compete for keys | A global unique key leaking which keys other tenants use (a 409 on "TF" tells you someone has "TF") |
| **`existsByProjectKey(key)` with no organization parameter** | The tenant filter scopes it: "exists in **my** organization" | Passing the org id by hand (and getting it wrong once) |
| **Create requires ADMIN or above** (`TenantContext.requireAtLeast`) ↺ D6 | Members read; admins create | Phase 4 replaces this with the permission evaluator |
| **Column `project_key`, JSON field `key`** | `key` is a keyword in HQL (map keys) | Query parse surprises on `p.key` |

#### Alternatives we didn't take

| Alternative | Why not | The concrete problem it would cause later |
|---|---|---|
| Plain `@Filter` enabled in the gate interceptor | The tutorial approach | With `open-in-view=false` there is no session in the interceptor that your service will use; and `findById` ignores a plain filter anyway (*verified*): `GET …/projects/{B's id}` → 200 |
| `@Filter(autoEnabled = true)` with a parameter resolver | Auto-enabled like `@TenantId` | Still ignores `findById` unless `applyToLoadByKey = true` (incubating), doesn't fill the column on insert, and doesn't refuse a mismatched insert: `@TenantId` is the same machinery with all three |
| `@SQLRestriction` (`@Where`) | Always on | Can't be parameterised: it can't say "the current tenant" (*verified*: no parameters) |
| Return 403 for another tenant's project | — | Confirms the id exists |
| A `ManyToOne Organization` on `Project` instead of `@TenantId Long` | Navigation | The tenant column must be a basic attribute for `@TenantId`; navigation isn't needed (the organization is the request's) |
| Composite FK `(organization_id, id)` target on projects now | Prepares Phase 4–5 | Nothing references projects yet; add it in the migration that adds the first referencing table (an unused unique index costs every insert) |

### What we're optimising for

**A forgotten `WHERE` can't leak across tenants**, **fail closed when no tenant is known**, and **one source for the tenant** (gate → context → resolver → session).

### Concepts

💡 **When Hibernate learns the tenant.** Once, when the session opens (*verified*). With `open-in-view=false`, that's the start of each `@Transactional` method. So the gate must have entered the tenant **before** the service's transaction starts (it has: interceptors run before the controller). With open-in-view **on**, a session would open at the start of the request, possibly before the gate (see the trap).
💡 **What the filter doesn't cover, and what does.** Native SQL and JDBC bypass it (Hibernate doesn't translate them) → keep tenant data out of native queries, or put the organization in them by hand and test it. A reference written by id (`getReferenceById(otherTenantsId)`) isn't checked → composite foreign keys from Phase 4 on. Everything else bypassing Hibernate (a reporting tool, `psql`) → RLS (§3.6).
💡 **Composite foreign keys.** When an issue (Phase 5) references a project, `foreign key (organization_id, project_id) references projects (organization_id, id)` makes "an issue in A pointing at a project in B" impossible in the database. The referenced pair must be unique, hence a `unique (organization_id, id)` on the parent, added when the first child appears.

### Requirements

#### The migration (V9): you apply it; the SQL is below (assumes D8 = A)

- [ ] `V9__create_projects.sql` is added exactly as given.

```sql
-- The first tenant-owned table: every row belongs to exactly one organization, and Hibernate's @TenantId filter
-- adds "organization_id = <current tenant>" to every query on it (decisions D4 and D8).
-- Phase 4 adds project membership, roles, settings and archiving on top.
create table projects (
    id              bigint       not null,
    organization_id bigint       not null,
    project_key     varchar(10)  not null,
    name            varchar(100) not null,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    created_by      varchar(50),
    updated_by      varchar(50),

    constraint pk_projects primary key (id),
    constraint fk_projects_organization
        foreign key (organization_id) references organizations (id) on delete cascade,
    -- Unique within its organization, not globally: two tenants can both have a "TF" project.
    -- Its index (leading organization_id) also serves "list this organization's projects".
    constraint uk_projects_org_key unique (organization_id, project_key),
    -- 2-10 characters, uppercase letters and digits, starting with a letter: becomes the issue-key prefix (TF-101).
    constraint ck_projects_key_format check (project_key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    constraint ck_projects_name_not_blank check (btrim(name) <> '')
);
```

| Column / object | Type | What it's for | Why the constraint |
|---|---|---|---|
| `id` | `bigint` | Key | PK; guessable, so every read is tenant-filtered |
| `organization_id` | `bigint not null` | The tenant (`@TenantId`) | FK `ON DELETE CASCADE`; not updatable (Hibernate makes the `@TenantId` attribute non-updatable: *verified*) |
| `project_key` | `varchar(10) not null` | `TF`, `PAY`: the issue-key prefix | Format `CHECK`; unique per organization |
| `name` | `varchar(100) not null` | Display name | Not blank |
| audit columns | as everywhere | Who created it | JPA auditing |
| `uk_projects_org_key` | unique `(organization_id, project_key)` | Key uniqueness per tenant | Its index also serves the tenant-filtered list |

**Verified on the scratch copy (2026-10-13):** `TF` in two organizations → both inserted · `TF` twice in one → `uk_projects_org_key` · `tf`, `T`, `1AB` → `ck_projects_key_format` · 11 characters → `value too long` · blank name → `ck_projects_name_not_blank` · unknown organization → `fk_projects_organization` · the tenant-filtered list and get-by-id use `uk_projects_org_key`'s index.

#### `TenantIdentifierResolver` and `TenantHibernateConfig` (`common/tenant`)

- [ ] The resolver returns the entered organization id when a request is active and the gate entered a tenant.
- [ ] Otherwise it returns `NO_TENANT` (`0L`), without touching the request-scoped bean outside a request.
- [ ] `isRoot` returns false for every value.
- [ ] A `HibernatePropertiesCustomizer` registers the resolver under `hibernate.tenant_identifier_resolver`.

**Done when:** login, refresh, "my organizations" and the existing tests still work with the resolver registered (if D1 = A, §3.0's suite proves it).

#### `Project` (entity, `project`)

- [ ] Extends `BaseEntity`; `@TenantId Long organizationId` mapped to `organization_id`; `projectKey` mapped to `project_key`; `name`.
- [ ] A factory `Project.create(key, name)` that never sets `organizationId`; no setters.

#### `ProjectService` / `ProjectController` (`project`)

- [ ] `create(request)` requires ADMIN or above; a key that exists in this organization → 409 `DUPLICATE_PROJECT_KEY` (checked with the tenant-filtered `existsByProjectKey`; `uk_projects_org_key` is the guard).
- [ ] `list(pageable)` returns this organization's projects, sorted `projectKey` asc by default (`key`, `name`, `createdAt` allowed).
- [ ] `get(projectId)` uses `findById`; empty → 404 `PROJECT_NOT_FOUND`.
- [ ] No repository method takes an organization id; no native query touches `projects`.

| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/organizations/{orgId}/projects` | `{key, name}` (`key` 2–10 uppercase letters/digits, starting with a letter) | **201** `{id, key, name, createdBy, createdAt}` + `Location` | 400 `VALIDATION_FAILED` · 403 `ORGANIZATION_ROLE_REQUIRED` · 404 · 409 `DUPLICATE_PROJECT_KEY` |
| GET | `/api/v1/organizations/{orgId}/projects?page&size&sort` | — | **200** page | 404 |
| GET | `/api/v1/organizations/{orgId}/projects/{projectId}` | — | **200** | 404 `ORGANIZATION_NOT_FOUND` / `PROJECT_NOT_FOUND` (unknown **or another organization's**) |

**Done when:** alice (A) and bob (B) both create `TF` → 201 each; alice's `GET /organizations/{A}/projects/{bob's TF id}` → 404 identical to a random id's; the SQL log shows `organization_id=?` on that `findById`.

### Traps ⚠️

- **`null` from the resolver**: every session in the application throws "no tenant identifier specified" (*verified* in source), login included. Hence `NO_TENANT`.
- **"Root" as the default**: if "no tenant" mapped to root, every request outside the gate would see every tenant's projects. The default must match nothing.
- **Touching `TenantContext` outside a request**: `ScopeNotActiveException` in every `@Scheduled` job and slice test; check `RequestContextHolder.getRequestAttributes()` first.
- **The tenant is fixed when the session opens.** Anything that opens the session before the gate (open-in-view, a transaction started in a filter) captures `NO_TENANT` for the whole request (*inferred* from the verified session-open behaviour; OSIV stays off, Phase 0). Keep `spring.jpa.open-in-view=false`.
- **Setting `organizationId` yourself** from a path variable or body: Hibernate refuses a mismatch (`PropertyValueException`, *verified*), and a match is redundant. Leave it null.
- **Native queries are unfiltered.** `@Query(nativeQuery = true)` on `projects` reads every tenant (deliberate failure 2).
- **Slice tests have no request**: a `@DataJpaTest` on `Project` sees nothing and inserts into organization 0 (FK violation). Activate a request and enter a tenant in the test, or test through HTTP.
- **`@TenantId` fields must all have the same Java type** across entities (*verified*: `MappingException` otherwise). `Long` everywhere.
- **A cross-tenant test with one organization proves nothing.** Every isolation test needs two organizations with **the same** project key and ids from the other one.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| 1. Replace `@TenantId` with a `@FilterDef`/`@Filter` on `Project`, enabled inside `ProjectService` (`entityManager.unwrap(Session.class).enableFilter(...)`) | The list is filtered; **`GET …/projects/{B's id}` → 200 with B's project** (`findById` ignores a plain filter: *verified*) | Why `@TenantId` (its filter has `applyToLoadByKey = true`) |
| 2. Add `@Query(nativeQuery = true, value = "select * from projects where id = :id")` and use it in `get` | B's project → 200 | Filters live in Hibernate's SQL generation; native SQL bypasses them |
| 3. Make `TenantContext` a static `ThreadLocal` that's never cleared; run with `server.tomcat.threads.max=1`; add a temporary `GET /api/v1/debug/projects` (outside the org path) that lists projects; call `GET /organizations/{A}/projects` as alice, then the debug endpoint as bob | bob sees **A's projects** (with the request-scoped bean: an empty list) | A leaked tenant on a pooled thread is a cross-tenant leak; request scope can't leak |
| 4. Make the resolver return `null` when no tenant is entered | `POST /auth/login` → 500 "no tenant identifier specified" | Every session needs a tenant; fail closed with a value that matches nothing |
| 5. Make "no tenant" root (`isRoot` true for `NO_TENANT`), then call the debug endpoint from 3 | Every tenant's projects | Fail open; the default must match nothing |
| 6. Set `organizationId` in `create` from a request field, and send B's id as alice | 500 `PropertyValueException: assigned tenant id differs from current tenant id` (*predicted from the verified source*) | The tenant comes from the session, never from input |

### Build order

1. V9, `Project`, repository.
2. Resolver + registration; run the existing suite (or §3.0's) before anything else. → *Deliberate failure 4.*
3. Create, list, get. → *Deliberate failures 6, 1, 2.*
4. The isolation tests (§3.7). → *Deliberate failures 3, 5.*

### Tests

| Kind | Must prove |
|---|---|
| Unit | Resolver: no request → `NO_TENANT`; request without a tenant → `NO_TENANT`; entered → the id; `isRoot` false |
| Integration | `TF` in A and B · A's member: B's project id → 404 identical to random · list in A never contains B's · member creates → 403 · duplicate key in one organization → 409 · login/refresh still work with the resolver registered |

---

## 3.5 — Side task: profile, and the README (from §1.7 / §2.6; on the trim list)

### What we're building

`GET /api/v1/users/me` returns the caller's own profile; `PATCH /api/v1/users/me` changes the display name and the timezone. The response type decides what's public: never the hash, the lock state, the failure counter or the sessions. And the README catches up with Phases 2–3.

### How we're building it, and why

- The caller is the `AuthenticatedUser` (Phase 2); the account is loaded by its id, never by a path id: no IDOR possible.
- `PATCH` takes `{displayName?, timezone?}` where `null` means "leave unchanged". 💡 A record **can't tell "field missing" from "field sent as null"**; a real PATCH needs JSON Merge Patch or `JsonNullable`. Not now; write the limit in a comment.
- The change goes through an entity method on `UserAccount` (`@DynamicUpdate`, Phase 1 decision 27), so it never rewrites the lock columns. It replaces the unused private `setTimezone` (nit).
- It's an entity change, so JPA auditing sets `updated_by` (the contrast with §3.2's bulk updates).

| Choice | Problem it solves | Alternative not taken · its later problem |
|---|---|---|
| Timezone validated against `ZoneId.getAvailableZoneIds()` by a custom constraint (`common/validator`, like `MaxUtf8Bytes`) | Only IANA **region** ids (`Asia/Kolkata`) | `ZoneId.of` alone accepts `+05:30`, `UTC+1`, `Z`: fixed offsets ignore daylight saving, so Phase 9's due-date reminders fire an hour off for half the year |
| `null` = unchanged | Simple and safe for two optional fields | Merge Patch now: a library and a concept for two fields |
| `displayName` rules = registration's (`@NotBlank` when present, `@Size(max = 100)`, stripped) | One rule for one field | Different rules on create and update |

### Requirements

#### `ProfileService` / `UserController`

- [ ] `GET /users/me` returns id, email, username, displayName, timezone, role, emailVerified, createdAt, and nothing else.
- [ ] `PATCH /users/me` changes only the fields sent non-null, through one `UserAccount` method.
- [ ] A timezone that isn't an IANA region id → 400 with `field: timezone`.
- [ ] A blank display name → 400 with `field: displayName`.
- [ ] An `email` field in the body is ignored, never applied (email change: backlog).

| Method | Path | Request body | Success | Errors |
|---|---|---|---|---|
| GET | `/api/v1/users/me` | — | 200 profile | 401 |
| PATCH | `/api/v1/users/me` | `{displayName?, timezone?}` | 200 profile | 400 `VALIDATION_FAILED` · 401 |

**Done when:** `PATCH {"timezone":"+05:30"}` → 400; `PATCH {"timezone":"Asia/Kolkata"}` → 200 and `updated_by` = the username.

#### README

- [ ] Basic is gone from it; it documents login, refresh, logout, the cookie, bearer usage (Postman and curl `-c`/`-b`), and organizations: the gate, the roles, invitations, projects.

### Traps ⚠️

- `ZoneId.of("+05:30")` succeeds. Validate against the region id set.
- Returning `UserAccount` "just for GET": the hash is one Jackson annotation away from the wire.
- A load-then-save without `@DynamicUpdate` would rewrite `locked_until` and the counter (Phase 1, *verified*). It's there; don't remove it.

### Deliberate failures

| Create this | What you'll see | Lesson |
|---|---|---|
| Validate with `ZoneId.of` only; send `+05:30` | 200, stored | Offsets aren't timezones |

### Tests

| Kind | Must prove |
|---|---|
| Unit | The timezone validator: `Asia/Kolkata`, `UTC` pass; `+05:30`, `UTC+1`, `Z`, `Mars/Olympus` fail |
| Integration | GET has no hash, lock or counter fields · PATCH with one field leaves the other unchanged · `updated_by` = username |

---

## 3.6 — Optional: PostgreSQL row-level security on `projects` (first on the trim list; only inside the time-box)

### What we're building

A second wall under `@TenantId`: PostgreSQL itself refuses to return or write `projects` rows of any organization except the one the transaction declared, whatever SQL reaches it (native, JDBC, a mistake). Built for `projects` only, as the pattern.

### How we're building it, and why (the design, whether or not you build it)

1. **Two database roles**: the owner (`taskflow`, runs Flyway) and an application role (`taskflow_app`: `SELECT/INSERT/UPDATE/DELETE` on tables, no ownership, **no `BYPASSRLS`**). Table owners and superusers skip RLS; that's why the app can't keep connecting as the owner.
2. **`alter table projects enable row level security; alter table projects force row level security;`** and a policy: `using (organization_id = current_setting('app.org_id', true)::bigint)` with the same `with check` for writes. `current_setting(…, true)` returns `NULL` when unset, so an unset tenant matches nothing (fail closed).
3. **`SET LOCAL app.org_id = …` at the start of each transaction** (scoped to the transaction, so a pooled connection never carries it to the next one), from the same resolver value.
4. **Testcontainers must connect as `taskflow_app`**, or every RLS test passes with RLS bypassed (the container's default user is a superuser).

| Alternative | Why not | The concrete problem it would cause later |
|---|---|---|
| `SET` (session) instead of `SET LOCAL` | — | The setting survives on the pooled connection: the next transaction on it runs as the previous tenant |
| RLS with the app as table owner | Simpler | Owners bypass RLS unless `FORCE` is set; the policy silently does nothing |
| Tests as the container's default user | Simpler | Superuser bypasses RLS: green tests, no protection |

**Requirements, migration SQL and run steps are written when you choose to build it** (it's optional; nothing else depends on it). The interview answer is the four points above.

---

## 3.7 — Tests across the phase (you write them; estimates include them)

**New tools this phase**

| Tool | Use it for | Watch out |
|---|---|---|
| **`TestOrganizations`** (a plain class like `TestUsers`): creates an organization with an owner, adds members with roles, through repositories or JDBC | Every multi-tenant test | Not a bean (a new bean = a new context = a new container, testing guide §7) |
| **A two-tenant fixture**: alice owns A, bob owns B, carol is MEMBER of A, dave is ADMIN of A; A and B both have a `TF` project | The isolation matrix | One organization can't prove isolation |
| **`SET CONSTRAINTS ALL IMMEDIATE`** in JPA slices | The owner invariant in a rolled-back test | Without it the deferred check never runs (*verified*) |
| **`RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()))`** + entering a tenant | JPA slices on tenant-owned entities | Reset it after each test |
| The adjustable `Clock` and the parallel helper from §3.0 | Invitation expiry; accept and transfer races | Reset the clock |

**The isolation matrix** (integration; the never-cut part of this phase). Caller: alice (member of A only). Every row → **404, byte-identical bar timestamp, correlation id and path to the same request with id 999999999**:

| Request | Target |
|---|---|
| `GET /organizations/{B}` | another organization |
| `GET /organizations/{B}/members` | its members |
| `GET /organizations/{A}/projects/{B's TF id}` | another tenant's row through your own organization |
| `PATCH /organizations/{A}/members/{bob}` | a user who's a member of B only |
| `DELETE /organizations/{A}/invitations/{B's invitation id}` | another organization's invitation |
| `POST /users/me/invitations/{an invitation to bob}/accept` | someone else's invitation |
| `POST /organizations/{B}/projects` | writing into another organization |

And the positive pair for each: bob gets 200/201/204 on his own.

**Mutation checks: plant each, the suite must go red**

| Plant this | Caught by |
|---|---|
| The gate skips when `orgId` is missing | web slice: missing variable → 500 |
| The gate returns 403 for non-members | isolation matrix: 404 expected |
| `@TenantId` removed from `Project` | isolation matrix: B's project id → 404 |
| The resolver returns root for "no tenant" | integration: a non-tenant read sees projects (resolver unit test) |
| `uk_organization_memberships_one_owner` order swapped in transfer (promote first) | transfer test → 409 |
| `AND role = :expectedCurrent` dropped | parallel transfer test: two 204s or a commit-time 409 |
| Invitation accept without the email in the `WHERE` | isolation matrix: someone else's invitation → 404 |
| Strictly-below → at-or-below | matrix: admin invites ADMIN → 403 expected |
| Organization creation without the owner membership | create test: 201 expected (gets 409) |

📊 **Measure:** suite time and container count at the end of §3.1 and of the phase (Phase 2 ended at 83 tests, 12 s, 2 containers). The gate's lookup plan with real rows (expect `uk_organization_memberships_org_user`): insert ~10k memberships in a scratch database and `explain analyze`.

---

## Definition of done

- [x] D1–D8 decided and recorded with dates. (2026-10-13)
- [x] (D1 = A) §3.0's 11 tests green and mutation-checked; suite count and time recorded. (2026-10-13: 18 tests, 14/14 mutations; 101 tests, 14 s, 2 containers)
- [x] Creating an organization makes the caller its owner; nothing can commit an organization without exactly one owner. (§3.1: tests + your run, rows 1 and 20–22, 2026-10-16)
- [x] `GET /organizations` lists only the caller's organizations. (§3.1: tests + your run, rows 4–5)
- [x] Every org-scoped request passes the gate; non-members get the same 404 as for a non-existent organization. (§3.1: tests + your run, rows 7–9, 11, 16)
- [ ] D6's matrix holds for all three roles; transfer requires the password and swaps roles atomically.
- [ ] Invitations: invite, list, revoke, my invitations, accept, decline; another user's invitation id → 404; expired ones can't be accepted.
- [ ] Projects: unique key per organization; another tenant's project id → 404 through `findById`.
- [ ] The isolation matrix passes and every mutation above turns it red.
- [ ] Login, refresh, reset and the per-request session check still work with the tenant resolver registered.
- [ ] (Side task) Profile GET/PATCH; README current.
- [x] Existing organization tests migrated, not disabled; cleanup deletes organizations before users. (§3.1)
- [ ] `PROJECT_CONTEXT.md` §2.1 (invitations, per D7) and §5 (Phase 3/4 rows, per D8) match what was built.
- [ ] Learning log and implementation reference created for Phase 3; small commits, one per sub-section, staged by file name. *(Learning log since 2026-10-13, implementation reference since 2026-10-16; commits: §3.0 `ec5acad`, §3.1 pending.)*

---

## Deliberate failures: the whole phase at a glance

| § | Break | Expect |
|---|---|---|
| 3.0 | The mutation column of the Tier-1 table | Each test red |
| 3.1 | Organization saved without its owner | 409 `ck_organizations_has_owner` at commit |
| 3.1 | Gate on the collection path too | `GET /organizations` → 500 |
| 3.1 | Gate skips a missing `orgId` | Another organization's members → 200 |
| 3.1 | 403 for non-members | An existence oracle |
| 3.1 | `TenantContext` from `@Async` | `ScopeNotActiveException` |
| 3.2 | Promote before demote | 409 one-owner index |
| 3.2 | Ignore step 2's row count | 409 at commit (owner trigger) |
| 3.2 | No expected role in the `WHERE` | Lost update / unexplained 409 |
| 3.2 | At-or-below | Admin creates a peer |
| 3.2 | Bulk update without `updated_by` | Stale audit column |
| 3.3 | Accept by id only | Someone else's invitation accepted |
| 3.3 | Email not normalised | Lowercase `CHECK` |
| 3.3 | Re-invite without revoking | 409 open-invitation index |
| 3.3 | Two accepts at once | One 200, one 404 |
| 3.3 | Admin invites ADMIN | A peer |
| 3.4 | Plain `@Filter` | B's project via `findById` → 200 |
| 3.4 | Native query | B's project → 200 |
| 3.4 | Uncleared `ThreadLocal`, one thread | Another tenant's projects |
| 3.4 | Resolver returns `null` | Login → 500 |
| 3.4 | "No tenant" = root | Every tenant's projects |
| 3.4 | `organizationId` from input | `PropertyValueException` |
| 3.5 | `ZoneId.of` only | `+05:30` stored |

---

## Explicitly NOT in Phase 3

Organization settings / default workflow (**Phase 6**, when workflows exist) · renaming or deleting an organization · sending invitation emails (deferred, `PROJECT_CONTEXT.md` §6) · auto-joining on registration · an audit log of membership changes (**Phase 7**) · notifying users of role changes (**Phase 9**) · tenant-scoped async or scheduled work and its explicit tenant scope (**Phase 9**) · the cleanup of expired invitations (**Phase 9**'s job) · project membership, project roles, settings, archive (**Phase 4**) · per-tenant rate limits (**Phase 10**) · platform-admin access to every tenant (support tooling: not built; `UserRole.ADMIN` grants **nothing** inside organizations) · schema- or database-per-tenant, subdomains (D2, D3) · SSO, SCIM, seats, billing · roles in the JWT (D3) · OpenAPI (proposed: Phase 11).

---

## 🎯 Interview questions: answer these *during* Phase 3

1. How do you keep one tenant's data away from another? What happens when a developer forgets the filter in a new query?
2. Shared schema, schema per tenant, database per tenant: when would you pick each?
3. `@Filter` vs `@TenantId` vs `@Where`/`@SQLRestriction`: what does each cover, and what doesn't it?
4. Where does the tenant come from on each request, and why isn't it in your JWT?
5. Request-scoped bean or `ThreadLocal` for the current tenant? What happens with `@Async`, and what happens when a `ThreadLocal` isn't cleared?
6. What does Spring inject when a singleton depends on a request-scoped bean?
7. How do you guarantee an organization always has exactly one owner, even during a transfer?
8. Why must a transfer demote before it promotes?
9. Why 404 and not 403 for an organization you're not in? When is 403 right?
10. How does your invitation design stop a forwarded invite from letting a stranger in?
11. What can't Hibernate's tenant filter protect against, and what would you add?
12. Why can a `@DataJpaTest` pass while violating a deferred constraint?
13. How does PostgreSQL row-level security work, and why do Testcontainers tests silently bypass it?
14. A removed admin still has a valid access token. What can they do, and for how long?

---

## Session split (~8h, plus §3.0's own ~3h 30m)

| Session | Sections | Est. | Actual |
|---|---|---|---|
| 0 | §3.0 Tier-1 test debt (if D1 = A; own budget) | ~3h 30m | |
| 1 | §3.1 memberships, the gate, my organizations, members | ~2h | |
| 2 | §3.2 roles, removal, leave, transfer | ~1h 30m | |
| 3 | §3.3 invitations | ~1h 45m | |
| 4 | §3.4 projects and the tenant filter, the isolation matrix | ~1h 45m | |
| 5 | §3.5 profile, README | ~1h | |
| (6) | §3.6 RLS, optional | ~2–3h | |

Over budget? **Trim in this order:** §3.6 RLS → §3.5 profile (the README stays) → the password on transfer (owner-only stays) → decline (accept stays) → the organization-side invitation list (invite and revoke stay). **Never trim:** the gate and its 404, `@TenantId` on projects, the owner invariant, the conditional statements, the isolation matrix and its mutation checks.

**Before session 1:** read Hibernate's user guide chapter *Multitenancy* (discriminator / partitioned data) and `TenantIdBinder` + `AbstractSharedSessionContract.setUpMultitenancy` in `~/.m2` (both short), and Spring's reference on *Scoped Beans as Dependencies*.

---

## Phase 3 notes (15 min at the end; these become interview stories)

**What I built:**

**What confused me:**

**What I learned:**

**Traps I actually hit:**
