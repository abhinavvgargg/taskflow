# Phase 3 — Learning Log (organizations & multi-tenancy)

> Organised by **theme**, not by sub-section (rule since 2026-10-01). Sub-section detail (briefs, requirements, per-step deliberate failures, test plans) lives in `PHASE_3_REQUIREMENTS.md`.
> Companions: `../phase-2/PHASE_2_LEARNING_LOG.md` (its §8 is the backlog this phase started from) · `../phase-1/SECURITY_TESTING_GUIDE.md` §8 (the test tools added in §3.0).
> **Covers so far:** §3.0 Tier-1 test debt (2026-10-13); §3.1 memberships and the tenant gate (built 2026-10-15, code and tests written by Claude at your request; `OPTIONS` fix after review 2026-10-16; your Postman run passed 2026-10-16). Decisions D1–D8 taken 2026-10-13 (all the recommendations).

---

## 1. What was built

| Capability | How it works | Key pieces |
|---|---|---|
| **An adjustable test clock** | Real time plus an offset a test can move forward; `@Primary` in the shared test configuration, so every `Clock`-injecting bean (issuer, the JWT decoder's expiry check, sessions, lockout, tokens) reads it, and no new context starts | `AdjustableClock`, `TestcontainersConfiguration` |
| **Logins that return both credentials** | `TestLogins.login` returns `LoginTokens(accessToken, refreshToken)`; helpers build a `Cookie` header, read a `Set-Cookie`, and read the `sid` claim out of a token | `TestLogins` |
| **Real races** | `Concurrently.run(n, call)`: N threads, released together by a latch, every result returned | `Concurrently` |
| **"Indistinguishable" assertions** | Compare two error bodies without `timestamp`, `correlationId`, `instance` | `ProblemBodies` |
| **Memberships and the owner invariant** (§3.1) | `organization_memberships` (V7): one row per organization × user with a role. The database guarantees exactly one owner: a partial unique index refuses a second `OWNER` at once, and a deferred constraint trigger refuses, at commit, any organization left without one (a forgotten insert, a removed or demoted owner, a deleted owner account). Existing organizations got their creator as owner by backfill | `V7__create_organization_memberships.sql`, `OrganizationMembership`, `OrganizationMembershipRepository` |
| **Creating an organization makes you its owner** (§3.1) | The organization and the `OWNER` membership are saved in one transaction; if the second insert were missing, the commit fails with 409 `ck_organizations_has_owner` and nothing is kept | `OrganizationService.create` |
| **The tenant gate** (§3.1) | A `HandlerInterceptor` on `/api/v1/organizations/*` and `/*/**`: reads the `{orgId}` path variable by name, looks up the caller's role (one indexed query), and either fills the request-scoped `TenantContext` or answers the same 404 `ORGANIZATION_NOT_FOUND` a non-existent id gets. A handler without `{orgId}` is a 500 (fail closed) | `TenantGateInterceptor`, `TenantWebConfig`, `MembershipLookup` → `JpaMembershipLookup` |
| **One source for "the current organization"** (§3.1) | `TenantContext` (`@RequestScope`): entered once by the gate; services read the organization id and role from it and never from the path. `requireAtLeast(role)` is the 403 for "member, but not enough" | `TenantContext`, `OrganizationRole` (`isAbove`, `isAtLeast`), `TenantErrorCode` |
| **"My organizations", get one, list members** (§3.1) | The list shows only the caller's organizations with their role (it used to show everyone's); get and members are behind the gate; members show user id, username, display name, role, joined-at, and no email | `OrganizationController`, `MemberController`, `MembershipService` |
| **Commit-time constraint names** (§3.1) | The 409 handler now names a constraint that fails at commit too (it only could for statement-time failures), by reading the driver's exception with Hibernate's own PostgreSQL rules | `CommonErrorUtility` |
| **18 Tier-1 tests** | Session revocation and IDOR (3), refresh rotation, reuse, superseded, the race, the dead-session rollback (4), reset and change revocation, single-use reset tokens and their race, wrong current password (5), lockout and enumeration (3), `prod` without a key (3) | `SessionRevocationIntegrationTest`, `RefreshIntegrationTest`, `PasswordSessionsIntegrationTest`, `LoginLockoutIntegrationTest`, `JwtKeyConfigTest` |

**Not built yet:** §3.2–§3.6. **Tests:** Tier 2 of the carried debt is owed to Phase 11 (list in `PHASE_3_REQUIREMENTS.md` §3.0, *Out*).

### Evidence

| | |
|---|---|
| **Verified by me (2026-10-13)** | Suite before §3.0: **83 tests, 12 s wall, 2 Postgres containers**; after: **101 tests, 14 s, 2 containers**. The three classes with races passed 5 repeated runs. All **14 mutations** caught, each planted alone in a scratch copy of the project (deleted afterwards; your `src/main` was never edited). Boot 3.5.16's client detection order (Apache → Jetty → Reactor → JDK → simple) and that the JDK builder configures no cookie handler, read in `spring-boot-3.5.16-sources.jar`. |
| **Verified by me (2026-10-15, §3.1)** | Suite: **136 tests, 0 failures, 18.6 s wall, still 2 Postgres containers** (35 tests added or rewritten, including the 2 for the `OPTIONS` fix on 2026-10-16). **15 mutations** caught, each planted alone in a scratch copy (deleted afterwards). Deliberate failures 1–5 observed in the scratch copy: results in `PHASE_3_REQUIREMENTS.md` §3.1. Read in sources: Spring Data JPA 3.5.13 qualifies a sort property with the root alias unless it starts with a join variable (`HqlSortedQueryTransformer.visitJoinPath`, `JpaQueryTransformerSupport.shouldPrefixWithAlias`); `@DataJpaTest` includes `JdbcTemplateAutoConfiguration`; Hibernate 6.6.53's PostgreSQL constraint-name extractor works from SQLState + message. spring-webmvc 6.2.19 answers an unmapped `OPTIONS` with its own `HandlerMethod` from `handleNoMatch`, with no path variables stored (`handleMatch` stores them, and it doesn't run). Your dev database was **not** touched: it is still at V6 until you start the app. |
| **Verified for the brief (2026-10-13)** | V7–V9 on a scratch copy of the dev database (dropped afterwards); the Hibernate 6.6.53 and Spring 6.2.19 behaviour listed in the brief's D4 and §3.1. |
| **Reported by you (2026-10-16, §3.1)** | Your Postman + SQL run: **all 23 rows passed as expected** (V7 applied with the expected backfill; create → `OWNER`; "mine" only; the non-member 404 identical to a non-existent id's and to `/abc`, checked by a Postman test script; members without email; sorts and their 400s; carol's removal → 404 on her next request with the same token; `OPTIONS` → 200 with `Allow` for member and non-member alike; 401 without a token; the four database invariants refused by name). The console's "one gate query per request" check wasn't reported separately. |
| **Not proven** | Tier-2 rules (wrong `aud`, `alg: none`, CORS rows, registration races, claim contents): still manual-only. |

---

## 2. Decisions

Dated, with alternatives, in `PHASE_3_REQUIREMENTS.md`'s Decisions table. One line each:

- **D1** Tier-1 test debt in its own block before §3.1 (§3.0, done); Tier 2 in Phase 11; Phase 3's own tests not deferrable. You first chose to write §3.0 yourself, then asked me to write it (2026-10-13).
- **D2** One shared schema; tenant-owned rows carry `organization_id`.
- **D3** The organization is named in the path (`/organizations/{orgId}/…`); membership and role are read from the database on every such request.
- **D4** Hibernate `@TenantId` + the membership gate + composite FKs (from Phase 4); row-level security is optional (§3.6).
- **D5** The current tenant lives in a request-scoped bean.
- **D6** Exactly one owner; you act on and grant only roles strictly below yours; ownership moves only by transfer.
- **D7** Invitations bound to the verified email, no token, accepted from "my invitations".
- **D8** A minimal Project (create, list, get) pulled forward from Phase 4 as the first tenant-owned table.
- **OpenAPI** moved to Phase 11.
- **The test clock ticks** (real time + offset), instead of the brief's `set(Instant)`: existing tests keep real time.
- **§3.1 was written by me, code and tests, at your request** (2026-10-15), in the brief's build order. Small choices made while building, beyond the brief: `OrganizationRole.isAtLeast` next to `isAbove` (so `requireAtLeast` doesn't rebuild the rule); the gate lets non-controller handlers through (they run no code of ours, and an unknown path keeps its normal 404 instead of a 500); the 404's detail carries no id (otherwise two misses differ); "my organizations" may also be sorted by `id` (its default sort names it); `TestUsers.deleteAll` deletes organizations first; `CommonErrorUtility` names commit-time violations (a gap deliberate failure 1 exposed). After review (2026-10-16): the gate checks for a `@Controller` bean instead of any `HandlerMethod`, so a plain `OPTIONS` is no longer a 500.

---

## 3. Lessons, by theme

***(Hit)*** = actually happened in this project.

### Testing time

- **A clock that keeps ticking is safer than a fixed one as a shared test default.** A `Clock.fixed` bean in the shared configuration would freeze time for every integration test, including ones that never thought about time; two requests a millisecond apart would see the same instant, and anything comparing "now" across requests could behave differently from production. `AdjustableClock` is real time plus an offset: tests that don't touch it get real time, tests that need "11 seconds later" call `advance`. The rule now: shared test infrastructure must change nothing for tests that don't use it.
- **One clock moves everything that reads it, including things you didn't think of.** The JWT decoder's expiry check uses the injected `Clock` (Phase 2 wired it that way on purpose). So advancing the clock 15 minutes doesn't only end a lockout: it also expires every access token you obtained before. The rule: when a test moves the clock past the access-token lifetime, it logs in again afterwards.
- **A shared, mutable bean must be reset before each test.** The clock is a singleton in a cached context: an offset left by one class leaks into the next, and the failure shows up in a test that never touched the clock. Every class that moves it calls `reset()` in `@BeforeEach`.
- **Put test-only beans in the configuration everyone already imports.** A `@MockitoBean Clock` or a per-class `@TestConfiguration` would have been a new context: a new application start and a new Postgres container (Phase 0 measured this). Declared next to the capturing email sender, the clock cost nothing: still 2 containers.

### Testing cookies and HTTP clients

- **Know which HTTP client your test uses, because it decides what's automatic.** Boot 3.5 picks the first client on the classpath: Apache, Jetty, Reactor, then the JDK's `HttpClient`, then the old `HttpURLConnection` (*verified* in the source). This project has none of the first three, so tests use the JDK client, with **no cookie jar**. That's a feature here: a test sends exactly the cookie it means to, which is how it presents an *old* refresh token on purpose. (Had it fallen back to `HttpURLConnection`, an `Origin` header would have been silently dropped: it's on that client's restricted-header list. *From memory, not checked in the JDK source*; worth checking before §2.5's Tier-2 CORS tests, which send `Origin`.)
- **A JWT is readable, so tests can read it too.** `TestLogins.sessionIdOf` parses the `sid` claim without the key. Phase 2's first lesson ("signed, not encrypted") becomes a test convenience: no extra request to find "this device's" session.

### Testing races

- **A race test must make the requests overlap, and must assert counts.** Twenty threads started in a loop often don't overlap at all: the first finishes before the last starts, and a check-then-act bug passes. `Concurrently` makes every thread wait on one latch and releases them together. The assertions are counts ("exactly one 200, nineteen 401"), never "thread 3 won", because the winner is the scheduler's choice. *(Seen: with check-then-act planted, only 10 of 20 responses were 401.)*
- **"No 500" is part of a race's contract.** A broken consume doesn't only produce two winners: the loser's insert collides with the partial unique index, which surfaces as a 409 or 500. Asserting "nineteen 401s" catches both at once.

### Proving "no oracle"

- **"Indistinguishable" means comparing whole bodies, minus what differs on every request.** Checking only the status (404 vs 404) misses a different `detail` or an extra property. `ProblemBodies` strips `timestamp`, `correlationId` and `instance`, and the rest must be equal. *(Seen: mutation 10 changed only the `detail` of the locked-account answer, and the comparison caught it.)*
- **Every "nothing happened" test needs a positive pair.** "A reset for an unknown email sends no mail" passes just as well if the mail sender is broken. The test also requests a reset for a known email and expects a mail: now the empty inbox means something.

### What a failed login must leave behind

- **A transactional login doesn't lose the lockout counter, but it does lose the history.** *(Corrected in my brief: I had written "the counter".)* `LoginAttemptService.recordFailure` runs in `REQUIRES_NEW`, so it commits on its own even if `login` is wrapped in a transaction that rolls back. What rolls back is the `LOGIN_FAILED` event, which joins the caller's transaction. *(Seen: mutation 8, `@Transactional` on `login` → 0 `LOGIN_FAILED` events instead of 4, counter still 4.)* The rule from Phase 1 stands ("login is not transactional"), and the test now asserts both records.
- **Lockout resets the counter to zero when it locks.** After the fifth failure, `failed_login_attempts` is 0 and `locked_until` is set (the lock replaces the count). A test asserting "5" after the fifth failure would be wrong; the test asserts 4 after four, then the lock.

### The tenant gate: where it runs and how it fails

- **Put the check at the latest point that already has everything it needs.** The gate needs two things: who is calling (known after the security chain) and which organization the URL names (known after Spring MVC has matched the route and parsed the path variables). A servlet filter runs too early for the second, so it would have to parse URLs by hand; a check inside every service would eventually be forgotten in one. A `HandlerInterceptor` runs right after the route is matched, and anything it throws goes through the same `@RestControllerAdvice` as everything else, so a refusal looks like every other error body.
- **A gate must fail when its input is missing, never skip.** The gate reads the path variable by its name, `orgId`. A controller mapped as `/organizations/{id}/…` would give it nothing. If "nothing" meant "let it through", any user could reach any organization through that one endpoint. So a missing `orgId` is a 500 (a programming error, loud in the first test). *(Seen: with the gate changed to skip, the mis-named probe endpoint answered 200 to a stranger.)*
- **The URL patterns decide where the gate runs, and fail-closed makes a wrong pattern loud.** The gate is registered for `/organizations/*` and `/organizations/*/**`, not `/organizations/**`, because the collection itself (create, "my organizations") names no organization. *(Seen: registered for `/**`, "my organizations" and create both became 500, and so did two unrelated security tests that list organizations.)*
- **Gate our controller methods, all of them, and nothing else.** Two kinds of handler under `/organizations/{x}/…` run none of our code. A path no controller maps ends in Spring's static-resource handler (404). A plain `OPTIONS` is answered by a `HandlerMethod` that Spring builds itself when no mapping matches, and because nothing matched, no path variables are stored. Gating either one makes "fail closed" turn a harmless answer into a 500. So the gate's question is "is this a method of a `@Controller` bean?", not "is this a `HandlerMethod`?". *(Hit, found in review on 2026-10-16: I first wrote `instanceof HandlerMethod`, and `OPTIONS /api/v1/organizations/{id}` was a 500 with an ERROR stack trace, for any signed-in user, as often as they liked. No data leaked, since every id gave the same 500, but it was a free way to flood the error log. Reproduced in a slice test and through the real server before fixing.)* Skipping every `OPTIONS` request would also have stopped the 500, but an `OPTIONS` endpoint we map ourselves would then skip the gate. The rule now: before failing closed on a missing input, make sure the handler is one the check was written for. "Fail closed" protects our code, and Spring's internals aren't our code.
- **"Not a member" and "doesn't exist" must be byte-identical, and that includes the `detail`.** Organization ids come from one global sequence, so they're guessable. A 403 for "exists but not yours" tells an attacker which ids are real; so does a 404 whose detail says "No organization 203". The detail is a constant ("Organization not found") and the id is only in `instance`, which differs on every request anyway. *(Seen: a 403 was caught by three tests; a detail containing the id was caught by the whole-body comparison.)* A non-numeric id is the same 404, not a 400, for the same reason.

### Request scope: one tenant per request, and nowhere else

- **A request-scoped bean is the safe home for "the current organization".** Spring creates one `TenantContext` per HTTP request and throws it away when the request ends; singletons receive a proxy that finds the current request's instance. Nothing is left on a pooled Tomcat thread for the next request to inherit, which is the classic `ThreadLocal` leak.
- **Outside a request, it fails loudly, and that's the point.** *(Seen, deliberate failure 5: an `@Async` method reading `TenantContext` threw `ScopeNotActiveException` on the executor thread, "Scope 'request' is not active for the current thread", and the request became a 500.)* Background work (Phase 9) must be handed the organization id explicitly; it can't read it from the air.
- **Services take the organization id from the context, never from the path.** Today they're equal. The day a body field or a second path variable is used instead, the gate would have checked one value and the query would use another. The controllers don't even bind `{orgId}`.
- **Entering twice is a bug, so it throws.** Two gates, or code entering a second organization mid-request, would silently switch tenants. `enter` refuses a second call.

### Invariants the database enforces

- **Some rules are about other rows: a `CHECK` can't express them, a constraint trigger can.** "Every organization has an owner" is about the memberships table, not the organization row. V7 uses a constraint trigger, and makes it **deferred**: it runs at commit, so a transfer can pass through a moment with no owner between its two updates, but can never commit that moment. "At most one owner" is a partial unique index, checked immediately.
- **A deferred check fails at commit, after your code has returned.** *(Seen, deliberate failure 1: with the owner membership removed from `create`, every `save()` succeeded, the method returned, and the request still ended in 409, with zero organization rows in the database.)* The database refused what the code forgot.
- **At commit, the error arrives in a different shape, and the constraint name was lost.** A violation during a statement reaches us as Hibernate's `ConstraintViolationException`, which carries the constraint name. A violation at commit doesn't: Hibernate reports "Unable to commit against JDBC Connection" around the driver's raw exception, Spring translates it by SQLState into a `DataIntegrityViolationException`, and our handler, which only looked for Hibernate's exception, found no name. *(Seen: the 409 said `constraint=null`. The brief had predicted the name: it was right about the 409, wrong about the name.)* The fix reads the name from the driver's exception with Hibernate's own PostgreSQL rules, so both paths give the same body.
- **Tests that roll back never reach commit.** `@DataJpaTest` rolls every test back, so a deferred trigger never runs there: a test that persists an organization without an owner passes. To prove the invariant, the test flushes and then runs `SET CONSTRAINTS ALL IMMEDIATE`, which runs the pending deferred checks on the spot. In an integration test that commits, a single autocommit statement is enough: deleting an owner's user row by SQL fails at the end of that statement.
- **Shared test databases carry one class's leftovers into the next.** Every integration class shares one Postgres container. An organization left behind by one class would make the next class's "delete all users" fail on the owner trigger. `TestUsers.deleteAll` now deletes organizations first, in one place, instead of trusting every class to be organization-free.

### Sorting a joined query with Spring Data

- **Spring Data prefixes sort properties with the query's first alias, unless they start with a join variable.** For `from OrganizationMembership m join UserAccount u …`, sorting by `username` becomes `m.username`, which doesn't exist. It leaves `u.username` alone, because `u` is registered as a join variable (*read in the Spring Data JPA 3.5.13 source*). So the API's sort names are translated once in `MembershipService` (`username` → `u.username`, `joinedAt` → `createdAt`), and "my organizations" is written with `Organization o` first so `name` and `createdAt` resolve against it directly.
- **The allow-list protects the translation.** Only names in the controller's list reach the query (`?sort=email` is 400 `INVALID_SORT_PROPERTY`), so a client can't sort by, and so probe, a column it can't see.

### Testing startup failures

- **Test "refuses to start" with the smallest context that can fail for that reason.** A full `prod` run also lacks an `EmailSender`, so it fails for a different reason first, which is why Phase 2 never proved this. `ApplicationContextRunner` starts only `JwtProperties`, `JwtKeyConfig` and a clock, with the profile set by an initializer. It runs in under a second, without containers.
- **An allow-list needs a test with a profile nobody listed.** Testing only `prod` would pass a block-list (`!prod`) too. The `staging` test is the one that tells them apart. *(Seen: mutation 11b turned only that test red.)*

---

## 4. Production practices, and what each prevents

| Practice | Prevents |
|---|---|
| Shared test beans in the one configuration every test imports | A new application context and Postgres container per test class |
| A ticking test clock, reset before each test | Frozen time for tests that never asked for it; offsets leaking between classes |
| Sending cookies explicitly in tests | Tests that pass because a jar sent a newer cookie than the one under test |
| Latch-released races, count assertions | Races that never overlap; scheduler-dependent tests |
| Whole-body comparison minus request-specific fields | "Same status, different detail" oracles slipping through |
| Positive pairs for negative tests | Green tests over broken infrastructure |
| A mutation check per test, in a scratch copy | Tests that can't fail; planted bugs left in the real code |
| The tenant gate in a `HandlerInterceptor`, reading `{orgId}` by name and failing closed, for `@Controller` methods only | A per-service check forgotten once; a mis-named path variable silently skipping the check; Spring's own handlers (a plain `OPTIONS`) becoming 500s |
| The same 404, with a constant detail, for "not a member" and "doesn't exist" | An oracle for which organization ids exist |
| The organization id read from `TenantContext` only | The gate checking one id while the query uses another |
| A request-scoped tenant holder | A previous request's tenant left on a pooled thread |
| Database-enforced owner invariant (partial unique index + deferred constraint trigger) | An ownerless or two-owner organization from any code path, including ones not written yet |
| `SET CONSTRAINTS ALL IMMEDIATE` in rolled-back tests of deferred rules | Tests that pass because the deferred check never ran |
| Deleting organizations before users in the shared test cleanup | One class's leftovers breaking the next class's cleanup |

---

## 5. Interview questions

**Answerable now**
1. **How do you test something that depends on time without sleeping?** Inject a `Clock` everywhere, and in tests replace it with one I can move. Mine is real time plus an offset, so tests that don't care see real time, and tests that need "past the lockout" move it 15 minutes.
2. **How do you test a race condition?** Real database, N threads held at a latch and released together, then assert counts: exactly one winner, everyone else the expected rejection, no 500. Never which thread won.
3. **How do you know your tests actually test something?** I plant the bug each test is about, one at a time, and the test must go red. In §3.0 all fourteen planted bugs were caught, each with a readable assertion.
4. **Why did your Spring test suite stay at two containers after adding five test classes?** Every class uses the exact same test configuration, so Spring reuses one cached context. Test-only beans (the clock, the email inbox) are declared in the shared configuration, not per class.
5. **How do you prove a login error doesn't leak whether an email exists?** Compare the whole error bodies for an unknown email, a wrong password and a locked account, ignoring only timestamp, correlation id and path. They must be equal.
6. **How do you test that the app refuses to start without a secret?** `ApplicationContextRunner` with just the configuration under test and the profile set, then assert the context failed and the message names the missing settings. Plus a test with a profile nobody listed, to prove it's an allow-list.

7. **How does your API stop a user from reading another customer's organization?** Every URL under `/organizations/{orgId}` passes a gate before the controller: it looks up the caller's membership in that organization in the database, on every request. No membership, and they get exactly the 404 a non-existent id gets, so they can't even learn that it exists. Members get a request-scoped context with the organization and their role, and services read the organization from that context, never from the URL.
8. **Why 404 and not 403 for someone who isn't a member?** Ids are guessable. A 403 says "this exists, you just can't see it", which lets anyone map out which organizations exist. A 403 is right only inside an organization you belong to, where you already know it exists, for example a member trying an admin action.
9. **Why an interceptor and not a filter for that check?** A filter runs before Spring MVC has matched the route, so it has no parsed path variables and its errors skip the normal error handler. An interceptor runs after the route is matched, so it can read `{orgId}`, and anything it throws becomes the same error body as the rest of the API.
10. **How do you guarantee an organization always has exactly one owner?** In the database. A partial unique index allows at most one `OWNER` row per organization, checked immediately. A deferred constraint trigger checks at commit that there's at least one, so an ownership transfer can briefly have zero between its two updates but can never commit that. It also stops anyone deleting an owner's account.
11. **How do you test a deferred constraint when your tests roll back?** Flush, then run `SET CONSTRAINTS ALL IMMEDIATE`, which forces the pending deferred checks to run now. Without it, the test passes because the check would only run at a commit that never happens.
12. **Why not keep the current tenant in a `ThreadLocal`?** Tomcat reuses threads. If anything skips the cleanup, the next request on that thread runs as the previous customer. A request-scoped bean is created and discarded by Spring with the request, and outside a request it throws, which is what you want for background jobs.

**Not answerable yet:** role changes, removal and ownership transfer (§3.2), invitations (§3.3), the Hibernate tenant filter (§3.4).

---

## 6. Commands

```bash
./mvnw test                                                      # everything: 136 tests, ~19 s, 2 containers (§3.1)
./mvnw test -Dtest=RefreshIntegrationTest                        # one class
./mvnw test -Dtest='RefreshIntegrationTest#twentyParallel*'      # one method (pattern)
for i in 1 2 3 4 5; do ./mvnw -q test -Dtest=RefreshIntegrationTest || break; done   # flakiness check for a race test
# mutation check WITHOUT touching your code: copy, plant, run, delete (what §3.0 did)
rsync -a --exclude target --exclude .git --exclude .idea ./ /tmp/tf-mut/ && cd /tmp/tf-mut   # edit one file, then:
./mvnw -q -Dmaven.gitcommitid.skip=true test -Dtest=<TheTestClass>; cat target/surefire-reports/*<TheTestClass>.txt
```

---

## 7. Deliberate failures and mutation checks

**§3.0, 2026-10-13: 14 of 14 caught.** Table with the failing assertion of each in `PHASE_3_REQUIREMENTS.md` §3.0. The ones worth remembering: the converter skipping the session check → a revoked session's token got **200** · the reuse branch throwing → **500** and the revocation rolled back · the grace measured from creation → a concurrent tab treated as theft · check-then-act consume → **10** of 20 parallel refreshes were 401 instead of 19 · reset consume ignoring its row count → **20 of 20** parallel resets succeeded · `revokeOwned` without the owner → alice logged bob out (**204**) · a transactional `login` → **0** `LOGIN_FAILED` events (counter intact) · a block-list instead of the allow-list → `staging` started with a throwaway key.

**§3.1, 2026-10-15/16: 15 of 15 caught; deliberate failures 1–5 all observed.** Tables in `PHASE_3_REQUIREMENTS.md` §3.1. The ones worth remembering: the gate skipping a missing `{orgId}` → a stranger got **200** · 403 for non-members → three tests red, in the slice and over HTTP · "my organizations" without the caller in the `WHERE` → alice saw bob's `globex` · members without the organization in the `WHERE` → **2** members instead of 1 · the role lookup without the organization → bob, owner of another organization, came back as **OWNER** of alice's · a role "above" itself → only the diagonal rows of the 9-pair table went red · the gate on `/organizations/**` → "my organizations" became **500** · create without the owner → **409** at commit, zero rows kept (and, before the fix, no constraint name) · the gate as first built → a plain `OPTIONS` was **500**.

---

## 8. Debt and what's next

- **Tier-2 tests** (Phase 11): §1.3 registration (duplicate 409 under a race, password never echoed), §1.4 verification details, §2.1 claim contents, cookie attributes and `no-store`, §2.2 `alg: none` / wrong key / **wrong `aud`** / converter rejections / the expiry boundary, §2.5 `CorsProperties` and the preflight table (needs a client that sends `Origin`: the JDK one does), Phase 0's `rejectedValue` test.
- **Commit §3.1** (code, V7, tests and docs, staged by name). Nothing is committed yet.
- *Done 2026-10-16:* V7 applied to your dev database and the §3.1 Postman run passed (all 23 rows).
- **Not measured yet:** the gate lookup's query plan with real row counts (the table is tiny everywhere so far).
- **Owed from the §3.1 review:** a test that an `OPTIONS` mapping we write stays gated (the reason the fix beats "skip every `OPTIONS`"), and the commented-out `findRoleByOrganizationIdAndUserAccountId`. Fix as you pass in §3.2.
- **Next: §3.2** (role changes, removal, leaving, ownership transfer).
- **Your 15-minute notes** for Phase 1 and Phase 2.
