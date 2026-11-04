# Phase 3 — Organizations & Multi-Tenancy (Summary, in progress)

> **One page for the whole phase:** what was built, what it taught, what was decided, and what to say about it in an interview. Started 2026-10-13. **The phase isn't finished:** this covers §3.0–§3.2 in full and where §3.3 stands on 2026-11-02. §3.3–§3.6 get added as they're done, and the summary is finalised when the phase closes.
> **Where the detail lives:** the brief for each sub-section with its traps, deliberate failures and run results ([PHASE_3_REQUIREMENTS.md](PHASE_3_REQUIREMENTS.md)), the full record of lessons by theme with all interview questions ([PHASE_3_LEARNING_LOG.md](PHASE_3_LEARNING_LOG.md)), the rebuild recipe and framework facts ([PHASE_3_IMPLEMENTATION_REFERENCE.md](PHASE_3_IMPLEMENTATION_REFERENCE.md)), the test tools ([SECURITY_TESTING_GUIDE.md](../phase-1/SECURITY_TESTING_GUIDE.md) §8), and the topic pages in [`knowledge/`](../knowledge/README.md). This summary links to them and doesn't replace them.

---

## 1. The phase in one paragraph

Phase 3 puts a boundary between customers. Before it, any signed-in user could list every organization and read any one by id. Now the organization is the **tenant**. A user belongs to it through a **membership** with a role (owner, admin, member). Every request under `/organizations/{orgId}/…` passes a **gate** that checks that membership in the database, and a stranger gets exactly the 404 a non-existent id gets. The database itself guarantees each organization has exactly one owner, even halfway through an ownership transfer. Members can be promoted, demoted, removed, or leave. Ownership moves only by a transfer that asks for the owner's password again. The phase opened by paying the test debt carried from Phases 1–2 (§3.0), and every sub-section since has shipped with its own tests and mutation checks. Still to come: invitations (§3.3, code drafted), projects scoped automatically by Hibernate's `@TenantId` (§3.4), the profile and README (§3.5), and optional row-level security (§3.6).

The phase's main lesson so far: **the check protects the request, not the query.** Every design is judged by one question: what does a member of organization A get when they send organization B's ids? The answer must always be the 404 they'd get for an id that doesn't exist.

---

## 2. What we built

| § | Built | Key classes | Status | Mechanisms explained in |
|---|---|---|---|---|
| 3.0 | The Tier-1 test debt from Phases 1–2 (18 tests), and the test tools: a movable clock, logins that return both tokens, real races, "same body" comparisons | `AdjustableClock`, `TestLogins`, `Concurrently`, `ProblemBodies`, `JwtKeyConfigTest` | ✅ 2026-10-13 | [testing 02](../knowledge/testing/02-security-race-and-time-tests.md) |
| 3.1 | Memberships (V7) and the one-owner rule in the database; create = become owner; the tenant gate; "my organizations", one organization, its members | `OrganizationMembership`, `TenantGateInterceptor`, `TenantContext`, `MembershipLookup`, `OrganizationRole`, `MemberController` | ✅ 2026-10-16 | [security 07](../knowledge/security/07-tenancy-and-org-authorization.md), [spring 03](../knowledge/spring/03-filters-interceptors-and-request-scope.md), [jpa 06](../knowledge/jpa/06-schema-constraints-and-invariants.md) |
| 3.2 | Change a member's role, remove, leave, transfer ownership with the password; one shared re-authentication | `MembershipService`, `MembershipWorkflow`, `Reauthentication` | ✅ 2026-10-20 | [jpa 05](../knowledge/jpa/05-bulk-updates-and-concurrency.md), [security 07](../knowledge/security/07-tenancy-and-org-authorization.md), [security 02](../knowledge/security/02-password-authentication.md) |
| 3.3 | Invitations by email (V8): invite, list, revoke; my invitations, accept, decline | `OrganizationInvitation`, `InvitationService`, `InvitationController`, `MyInvitationController` | ⏳ Code for all six endpoints drafted 2026-10-20, **uncommitted, no tests, not reviewed or run** | [security 07](../knowledge/security/07-tenancy-and-org-authorization.md) |
| 3.4 | Projects as the first tenant-owned table; Hibernate's `@TenantId` | — | Not started | [jpa 08](../knowledge/jpa/08-multi-tenancy-with-hibernate.md) |
| 3.5 | Profile (carried from §1.7 / §2.6) and the README | — | Not started | — |
| 3.6 | PostgreSQL row-level security on projects | — | Optional; design written | [jpa 08](../knowledge/jpa/08-multi-tenancy-with-hibernate.md) |

**Migrations:** V7 (`organization_memberships`, the owner trigger, backfill) applied and frozen. V8 (`organization_invitations`) written with the §3.3 draft, uncommitted. V9 (`projects`) is in the brief.

**Endpoints (built):**

| Endpoint | Behaviour |
|---|---|
| `POST /organizations` | 201; the caller becomes `OWNER` |
| `GET /organizations` | 200, **only the caller's** organizations, with their role |
| `GET /organizations/{orgId}` | 200 for members; anyone else gets the same 404 as a missing id |
| `GET /organizations/{orgId}/members` | 200, no emails |
| `PATCH /organizations/{orgId}/members/{userId}` | 200; 403 if the member or the new role isn't strictly below you; 409 if it changed meanwhile |
| `DELETE /organizations/{orgId}/members/{userId}` | 204; same rule |
| `DELETE /organizations/{orgId}/members/me` | 204; the owner gets 409 `OWNER_CANNOT_LEAVE` |
| `POST /organizations/{orgId}/ownership-transfer` | 204; owner only, needs the current password |

**Endpoints (drafted in §3.3, not yet run):** `POST/GET /organizations/{orgId}/invitations`, `DELETE …/invitations/{id}`, `GET /users/me/invitations`, `POST /users/me/invitations/{id}/accept`, `…/decline`.

The full tables with error codes are in [reference Part E](PHASE_3_IMPLEMENTATION_REFERENCE.md) and the §3.3 brief.

---

## 3. Concepts learned

**Tenancy: the two halves of isolation** → [security 07](../knowledge/security/07-tenancy-and-org-authorization.md), [jpa 08](../knowledge/jpa/08-multi-tenancy-with-hibernate.md)
- **The gate** answers "may this caller act in organization 42 at all?" **The scope** answers "does every query in this request see only organization 42's rows?" They fail independently. The gate without the scope leaks through ids from URLs; the scope without the gate lets anyone pick any organization. §3.1 built the gate; §3.4 builds the scope.
- **One shared schema, `organization_id` on tenant-owned rows** (D2). Schema- or database-per-tenant don't fit self-service signup.
- **The organization is named in the path, and the membership is read on every request** (D3), not put in the JWT. Removing or demoting someone takes effect on their next request.
- **Access tables vs tenant-owned data.** Memberships and invitations are read from both sides (the organization's and the user's), so they name `organization_id` or the user in every `WHERE` by hand. Projects belong to one tenant, so Hibernate scopes them automatically (§3.4).

**The gate: where it runs and how it fails** → [spring 03](../knowledge/spring/03-filters-interceptors-and-request-scope.md)
- **A `HandlerInterceptor`, not a filter.** It needs the caller (known after security) and the `{orgId}` path variable (known only after Spring MVC matched the route). Its exceptions go through the normal error handler, so a refusal looks like every other error.
- **Fail closed when the input is missing.** A handler without `{orgId}` is a 500, not a pass, or one mis-named endpoint opens every organization.
- **Gate only our controller methods.** Spring answers a plain `OPTIONS` with its own handler and stores no path variables; gating it turned a harmless answer into a 500.
- **The URL patterns decide where it runs:** `/organizations/*` and `/organizations/*/**`, not `/organizations/**`, because the collection itself names no organization.
- **"Not a member" and "doesn't exist" must be byte-identical**, detail included. Ids are guessable; a 403, or a detail with the id in it, tells an attacker which ids are real. 403 is right only *inside* your own organization.

**Request scope** → [spring 03](../knowledge/spring/03-filters-interceptors-and-request-scope.md)
- **"The current organization" lives in a `@RequestScope` bean.** Spring creates one per request and throws it away afterwards; singletons get a proxy that finds the current request's instance. Nothing stays on a pooled thread, which is the classic `ThreadLocal` leak.
- **Outside a request it throws** (`ScopeNotActiveException`, seen with `@Async`). Background work (Phase 9) must be handed the organization id.
- **Services read the organization from the context, never from the path.** Otherwise the gate checks one value and the query uses another. Entering twice throws.

**Invariants the database enforces** → [jpa 06](../knowledge/jpa/06-schema-constraints-and-invariants.md), [jpa 03](../knowledge/jpa/03-transactions.md)
- **"At most one owner"** is a partial unique index, checked at each statement.
- **"At least one owner"** is about other rows, so a `CHECK` can't express it; a **deferred constraint trigger** checks it at commit. A transfer can pass through a moment with no owner but can never commit it.
- **A deferred check fails at commit, after your code returned.** The error arrives in a different shape and, until we fixed it, without the constraint name.
- **Tests that roll back never reach commit.** Prove a deferred rule with `SET CONSTRAINTS ALL IMMEDIATE` after a flush.

**Read, decide, write with the value you read** → [jpa 05](../knowledge/jpa/05-bulk-updates-and-concurrency.md)
- **Read-then-write is a race unless the write repeats what was read.** `UPDATE … WHERE role = :expectedCurrent` matches 0 rows if someone changed it, and the API answers 409 `MEMBERSHIP_CHANGED`. The expected value works like a free version number.
- **The database referees with row locks:** the second update waits, then re-checks its `WHERE` against the committed row.
- **Statement order matters with immediate constraints:** a transfer demotes first, then promotes, or the one-owner index refuses it.
- **Bulk statements skip auditing**, so they set `updated_at` / `updated_by` themselves. They also leave loaded entities stale, so the response is read back with a fresh query.
- **A no-op shouldn't write** (same role → 200, no `updated_by` stamp).

**Who may act on whom (D6)** → [security 07](../knowledge/security/07-tenancy-and-org-authorization.md)
- **Two comparisons, not one:** the member being changed *and* the new role must both be strictly below you. Checking only the new role lets the owner "demote" themselves.
- **One rule in one place, tested as a full table** (`OrganizationRole.mayChangeRole`, `mayRemove`: 18 and 9 rows).
- **Leaving isn't removing yourself:** its own endpoint, `DELETE /members/me`. Spring prefers the literal `/members/me` over `/members/{userId}`.

**Sudo mode** → [security 02](../knowledge/security/02-password-authentication.md)
- **An irreversible action asks for the password, even with a valid token.** The token proves the browser logged in recently; the password proves the owner is there now.
- **Order:** the free checks first (are you the owner, is the target someone else), then the password, then the transaction. bcrypt runs **outside** the transaction so it doesn't hold a pooled connection.
- **One `Reauthentication` for every caller**, through the `AuthenticationManager`, so wrong passwords count toward lockout everywhere.

**Sorting a joined query** → [jpa 04](../knowledge/jpa/04-queries-projections-and-paging.md)
- **Spring Data prefixes sort properties with the first alias** unless they start with a join variable, so API sort names are translated once in the service, behind an allow-list.

**Testing time, races and "no oracle"** → [testing 02](../knowledge/testing/02-security-race-and-time-tests.md)
- **A ticking test clock (real time + offset) is a safe shared default;** a fixed one would freeze time for every test. Reset it before each test. Moving it 15 minutes also expires every access token, so log in again afterwards.
- **Put test-only beans in the configuration everyone imports.** A per-class mock or `@TestConfiguration` would start a new context and a new container.
- **Know your test HTTP client.** Boot picked the JDK client, which keeps no cookies: a test sends exactly the cookie it means to, old ones included.
- **A race test releases N threads from one latch and asserts counts** ("exactly one 200, nineteen 401"), never which thread won. "No 500" is part of the contract.
- **"Indistinguishable" means comparing whole bodies**, minus timestamp, correlation id and path.
- **Every "nothing happened" test needs a positive pair**, or a broken mail sender passes it.
- **A guard that only matters during a race needs a test that doesn't need the race:** call the statement with the stale value and expect 0 rows.
- **Pick test data that makes the condition the only thing in the way.** For each condition in a `WHERE`, ask which row only it would exclude.
- **Test "refuses to start" with the smallest context**, and with a profile nobody listed, to prove it's an allow-list.
- **A transactional login loses the history, not the counter** (the counter commits on its own); the test asserts both.

---

## 4. Decisions

The decisions you'd have to defend. The full table (D1–D8 with options) is in the [requirements](PHASE_3_REQUIREMENTS.md); all of them are indexed by topic in [decisions/INDEX.md](../knowledge/decisions/INDEX.md). Every recommendation was taken on 2026-10-13.

| Decision | Chosen | Why | What we turned down, and why |
|---|---|---|---|
| D1 Carried test debt | Tier 1 in §3.0 before §3.1; Tier 2 in Phase 11; Phase 3's own tests can't be deferred | Tests placed inside time-boxed sections were cut five times; §3.4 will hook every Hibernate session, including the untested flows | All now (too big); interleaved (gets cut again); all in Phase 11 |
| D2 Tenancy model | One shared schema + `organization_id` | Fits self-service signup | Schema per tenant (DDL per signup, N-schema migrations); database per tenant (a pool each) |
| D3 How a request names its organization | In the path; membership read on every request | Removal acts on the next request; the tenant is in every URL and log | A header, a JWT claim (stale until refresh), a subdomain, a slug |
| D4 Isolation | `@TenantId` + the gate + composite foreign keys (from Phase 4); row-level security optional | `@TenantId` covers `find()` and inserts (verified); a plain `@Filter` doesn't | Explicit `WHERE`s only (forgotten once); RLS as the default (a second DB role, fails open in Testcontainers) |
| D5 Current tenant holder | Request-scoped bean | Ends with the request; can't leak to the next one on a pooled thread | `ThreadLocal` (leaks if cleanup is skipped); passing ids everywhere |
| D6 Roles | Exactly one owner; act on and grant only roles strictly below yours; ownership moves only by transfer | Nobody can create a peer, so no escalation | "At or below" (an admin makes more admins); several owners |
| D7 Invitations | Bound to the verified email, no token, accepted from "my invitations" | A forwarded invitation can't let a stranger in; login already proves the inbox | A token link (forwardable), or token + email (a second secret for no gain) |
| D8 First tenant-owned data | A minimal Project pulled forward from Phase 4 | Memberships and invitations are access tables read from both sides | A filter with no entity to test it on |
| Test clock | Ticks (real time + offset) | Tests that don't care keep real time | The brief's `set(Instant)`: froze time for everyone |
| The gate's predicate (after review) | Gate methods of `@Controller` beans only | A plain `OPTIONS` was a 500 for anyone signed in | Skip every `OPTIONS`: an `OPTIONS` endpoint we map ourselves would then skip the gate |
| Commit-time constraint names | Read the name from the driver's exception with Hibernate's PostgreSQL rules | Deferred violations gave a 409 with `constraint: null` | Leaving the 409 nameless |
| Transfer | Owner check → not yourself → password (outside the transaction) → demote, then promote, in one transaction | Ownership can't be given away with a stolen token; never two owners, never none | Promote first (one-owner index refuses it); password inside the transaction (holds a connection during bcrypt) |
| `409 ALREADY_MEMBER` on invite (§3.3) | Accepted as a small email oracle | Only managers of that organization can ask, about their own members | Creating the invitation anyway and refusing at accept: a pointless open invitation |
| OpenAPI | Moved to Phase 11 | Phase 3 adds ~15 endpoints and Phase 4 reshapes authorization: documenting now means documenting twice | Doing it now |

---

## 5. Patterns to reuse

1. **Ask what a member of A gets with B's ids**, for every endpoint, and make the answer the same 404 as a missing id.
2. **Check at the latest point that has everything it needs** (an interceptor after route matching), and **fail closed** when an input is missing.
3. **One source for the current tenant** (request scope), never re-read from the path.
4. **Let the database enforce invariants about other rows:** a partial unique index for "at most one", a deferred constraint trigger for "at least one".
5. **Repeat the value you read in the write's `WHERE`;** 0 rows → 409 "changed, reload".
6. **Order statements around immediate constraints** (demote before promote).
7. **Bulk updates set their own audit columns, and the response is read back afterwards.**
8. **A permission rule is a function in one place, tested as a complete table.**
9. **Sudo mode** for irreversible actions: cheap checks first, password outside the transaction.
10. **Shared test infrastructure must change nothing for tests that don't use it.**
11. **Mutation-check every test in a scratch copy;** if a mutation survives, fix the test data, not just the test.
12. **Races: latch, real database, count assertions.**

---

## 6. Bugs, traps and deliberate failures

**What happened to us.**

| Bug | What happened | Rule now |
|---|---|---|
| The gate first checked `instanceof HandlerMethod` | `OPTIONS /api/v1/organizations/{id}` was a 500 with a stack trace, for any signed-in user, as often as they liked (found in review) | Before failing closed, make sure the handler is one the check was written for |
| The 409 handler only understood statement-time violations | A commit-time violation said `constraint: null` (the brief predicted the name) | Read the name from the driver's exception |
| Your first change-role rule checked only the new role | The owner could have demoted themselves, stopped only by the trigger as a confusing 409 (caught in review) | Two comparisons: the member and the new role |
| Mutation 13 survived the first test run | The "member of another organization" in the test was that organization's owner, whom the query excluded anyway | Pick data that only the condition under test would exclude |
| My brief said a transactional login loses "the counter" | The counter commits on its own; what's lost is the `LOGIN_FAILED` history | The test asserts both |
| Web slices stopped starting after §3.1 | `@WebMvcTest` loads interceptors and `WebMvcConfigurer`s, so the gate came in without its dependencies (the brief missed it) | Slices get a mocked `MembershipLookup` and `TenantContext` |

**Traps worth remembering**
- **"We check membership in the controller, so isolation is done."** The check protects the request; `findById` with an id from the URL still returns another tenant's row (§3.4 closes it).
- **`@TenantId` on memberships:** the gate's lookup runs before any tenant is entered, and "my organizations" reads across tenants.
- **Deferred checks don't run in `@DataJpaTest`,** which rolls back.
- **Shared test databases carry leftovers:** delete organizations before users, in one place.
- **Moving the test clock past 15 minutes expires every access token.**
- **SQL `now()` in a query ignores the injected clock;** pass `:now`.
- **Expiry can't go in a partial index** (`now()` isn't immutable), so re-invite revokes the old invitation first.
- **A race window you can't hit, widen, but only in a scratch copy.**

**Deliberate failures:** broken on purpose to see the error. All the tables are in the requirements (§3.0, §3.1, §3.2); highlights in [learning log §7](PHASE_3_LEARNING_LOG.md).

| Broke | What you saw | Lesson |
|---|---|---|
| Converter skips the session check | A revoked session's token got 200 | Tier 1 can fail |
| Check-then-act refresh, 20 in parallel | 10 of 20 were 401 instead of 19 | Latch-released races catch it |
| `@Transactional` on `login` | 0 `LOGIN_FAILED` events, counter intact | What really rolls back |
| A block-list instead of the allow-list | A `staging` profile started with a throwaway key | Test with an unlisted profile |
| Create without the owner membership | 409 at commit, zero rows kept | The database refuses what code forgets |
| Gate skips a missing `{orgId}` | A stranger got 200 | Fail closed |
| Gate on `/organizations/**` | "My organizations" became 500 | Patterns decide where it runs |
| 403 for non-members | An existence oracle, caught by three tests | 404 for both |
| `TenantContext` read from `@Async` | `ScopeNotActiveException` | Request scope ends at the thread |
| Promote before demote | 409 from the one-owner index on every transfer | Statement order |
| No expected role in the `WHERE` (window widened) | The late demote overwrote the new owner 6 of 6 times | The expected value as a version |
| No self-check on transfer | A 204 that did nothing | Order of checks |
| Bulk update without `updated_by` | `updated_by` still named the inserter | Bulk statements skip auditing |

---

## 7. Interview questions

Short answers, the way you'd say them. All of them are in [learning log §5](PHASE_3_LEARNING_LOG.md); the questions still to answer (the tenant filter, RLS) are at the end of the [requirements](PHASE_3_REQUIREMENTS.md).

1. **How does your API stop a user from reading another customer's organization?** "Every URL under `/organizations/{orgId}` passes a gate before the controller. It looks up the caller's membership in that organization, on every request. No membership, and they get exactly the 404 a non-existent id gets. Members get a request-scoped context with the organization and their role, and services read the organization only from there."
2. **Why 404 and not 403 for someone who isn't a member?** "Ids are guessable. A 403 says 'this exists, you just can't see it', which maps out every organization. 403 is right only inside an organization you belong to, like a member trying an admin action."
3. **Why an interceptor and not a filter?** "A filter runs before Spring MVC matches the route, so there are no parsed path variables, and its errors skip the normal handler. An interceptor runs after matching, reads `{orgId}`, and anything it throws becomes the same error body as the rest of the API."
4. **Why not keep the current tenant in a `ThreadLocal`?** "Tomcat reuses threads. If anything skips the cleanup, the next request on that thread runs as the previous customer. A request-scoped bean is created and discarded with the request, and outside a request it throws, which is what you want for background jobs."
5. **Why isn't the organization or role in your JWT?** "A token lives 15 minutes. If the role were in it, a removed admin would stay admin until it expired. Reading the membership per request makes removal act on the very next request."
6. **How do you guarantee exactly one owner?** "In the database. A partial unique index allows at most one owner row per organization, checked at each statement. A deferred constraint trigger checks at commit that there's at least one. So a transfer can briefly have zero between its two updates, but can never commit that."
7. **Why must a transfer demote before it promotes?** "The one-owner index is checked at each statement. Promoting first would mean two owners for a moment, and the index refuses it. Demoting first means zero for a moment, which only the deferred trigger checks, at commit."
8. **How do you test a deferred constraint when tests roll back?** "Flush, then `SET CONSTRAINTS ALL IMMEDIATE`, which runs the pending checks now. Without it the test passes because the check would only run at a commit that never happens."
9. **Two admins change the same member's role at once. What happens?** "Each reads the role, checks the rule, then updates with the role it read in the `WHERE`. The row lock makes the second wait; then the role isn't what it read, it matches nothing, and the API says 409, reload. Nobody's change is silently lost."
10. **Why ask for the password again when the user has a valid token?** "Because the action can't be undone and tokens get stolen. The token proves the browser logged in recently; the password proves the owner is there now. It's checked after the free checks and outside the transaction, because bcrypt is deliberately slow."
11. **Why doesn't JPA auditing fill `updated_by` here?** "Auditing runs when Hibernate flushes an entity. A `@Modifying` update goes straight to SQL, so the query sets the audit columns itself."
12. **How do you test time without sleeping?** "Inject a `Clock` everywhere and in tests use one I can move: real time plus an offset. Tests that don't care see real time; a lockout test moves it 15 minutes."
13. **How do you test a race condition?** "Real database, N threads held at a latch and released together, then count: exactly one winner, everyone else the expected rejection, no 500. Never which thread won."
14. **How do you know your tests test something?** "I plant the bug each test is about, one at a time, in a scratch copy, and the test must go red. Once a mutation survived; the test data made the guard redundant, so I changed the data."
15. **How do you prove an error doesn't leak whether something exists?** "Compare the whole error bodies, ignoring only timestamp, correlation id and path. They must be identical."
16. **How does your invitation design stop a forwarded invite from letting a stranger in?** "There's no token. The invitation is bound to an email, and you accept it from 'my invitations' while signed in with that verified email. Someone else's invitation id matches no row, so it's a 404."

---

## 8. Numbers measured

| What | Result |
|---|---|
| Suite growth | 83 tests, 12 s (Phase 2 close) → **101**, 14 s (§3.0) → **136**, 18.6 s (§3.1) → **223**, 23.2 s (§3.2). Always **2 Postgres containers** |
| Mutation checks | §3.0: **14 of 14** · §3.1: **15 of 15** · §3.2: **15 of 15** (one after strengthening a test) |
| Race tests | The three race classes in §3.0 and the transfer race each passed 5 repeated runs |
| Lost-update window | Widened to 300 ms in a scratch copy: with the expected role, 6 of 6 answered 409; without it, 6 of 6 overwrote the new owner. 25 runs without the widening never hit the window |
| Not measured | The gate lookup's query plan with real row counts (every table is still tiny) |

---

## 9. Deferred and carried debt

Status checked against the code and the working tree on 2026-11-02.

| Item | Where it is |
|---|---|
| Tier-1 test debt from Phases 1–2 | ✅ Paid in §3.0 |
| Tier-2 tests: registration details, verification details, claim contents, cookie attributes, `alg: none`, wrong key, wrong `aud`, the expiry boundary, CORS, Phase 0's `rejectedValue` | ⏸️ Phase 11 |
| §3.3 invitations | ⏳ Code drafted (uncommitted); still owed: review, tests, mutation checks, your Postman run, the reference and log updates, the commit |
| §3.2's wrap-up doc edits | Still uncommitted in the working tree |
| §3.4 projects + `@TenantId`, §3.5 profile + README, §3.6 RLS (optional) | Not started |
| Definition-of-done items still open | Invitations; projects; the isolation matrix and its mutations; login/refresh/reset still working with the tenant resolver; profile + README; `PROJECT_CONTEXT.md` §2.1 and §5 updated |
| `MembershipService.transferOwnership` is `public`, so a future caller could skip the password | Open (package-private would make sudo mode unskippable) |
| A stolen access token can lock the owner out with 5 wrong transfer passwords | ⏸️ Phase 10 rate limiting |
| §3.1 nits: a concurrent duplicate slug gets a generic 409; the role order is the rule; `CommonErrorUtility` builds a `PostgreSQLDialect` to borrow its extractor | Open |
| `409 RESOURCE_CONFLICT` for two concurrent re-invites of the same email (§3.3) | Accepted (right outcome, generic answer), inferred, not run |
| Background work needs the tenant passed explicitly | ⏸️ Phase 9 |
| Invitation emails, cleanup of expired invitations | ⏸️ Backlog / Phase 9 |
| OpenAPI | ⏸️ Phase 11 |
| Your 15-minute notes for Phases 1, 2 and 3 | All three templates still empty |

---

## 10. Read more

- **Rebuild a feature:** [reference Part D](PHASE_3_IMPLEMENTATION_REFERENCE.md) (step-by-step recipe) and [features/INDEX.md](../knowledge/features/INDEX.md) (Phase 3 rows → reference C1–C6)
- **Framework behaviour we relied on, checked in the source:** [reference Part F](PHASE_3_IMPLEMENTATION_REFERENCE.md)
- **Why we decided X:** [decisions/INDEX.md](../knowledge/decisions/INDEX.md)
- **A mechanism in depth:** [security 07](../knowledge/security/07-tenancy-and-org-authorization.md), [spring 03](../knowledge/spring/03-filters-interceptors-and-request-scope.md), [jpa 05](../knowledge/jpa/05-bulk-updates-and-concurrency.md), [jpa 06](../knowledge/jpa/06-schema-constraints-and-invariants.md), [jpa 08](../knowledge/jpa/08-multi-tenancy-with-hibernate.md) (for §3.4)
- **The test tools and how to read them:** [SECURITY_TESTING_GUIDE.md](../phase-1/SECURITY_TESTING_GUIDE.md) §8 and [testing 02](../knowledge/testing/02-security-race-and-time-tests.md)
- **Commands** (one test class or method, the flakiness loop, a mutation check in a scratch copy): [learning log §6](PHASE_3_LEARNING_LOG.md)
- **The §3.1 Postman run (23 rows) and its cleanup SQL:** the end of §3.1 in the [requirements](PHASE_3_REQUIREMENTS.md)
- **What it builds on:** [Phase 2 summary](../phase-2/PHASE_2_SUMMARY.md) · [Phase 1 summary](../phase-1/PHASE_1_SUMMARY.md) · [Phase 0 summary](../phase-0/PHASE_0_SUMMARY.md)
