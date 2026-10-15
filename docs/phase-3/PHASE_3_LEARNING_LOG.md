# Phase 3 — Learning Log (organizations & multi-tenancy)

> Organised by **theme**, not by sub-section (rule since 2026-10-01). Sub-section detail (briefs, requirements, per-step deliberate failures, test plans) lives in `PHASE_3_REQUIREMENTS.md`.
> Companions: `../phase-2/PHASE_2_LEARNING_LOG.md` (its §8 is the backlog this phase started from) · `../phase-1/SECURITY_TESTING_GUIDE.md` §8 (the test tools added in §3.0).
> **Covers so far:** §3.0 Tier-1 test debt (2026-10-13). Decisions D1–D8 taken 2026-10-13 (all the recommendations).

---

## 1. What was built

| Capability | How it works | Key pieces |
|---|---|---|
| **An adjustable test clock** | Real time plus an offset a test can move forward; `@Primary` in the shared test configuration, so every `Clock`-injecting bean (issuer, the JWT decoder's expiry check, sessions, lockout, tokens) reads it, and no new context starts | `AdjustableClock`, `TestcontainersConfiguration` |
| **Logins that return both credentials** | `TestLogins.login` returns `LoginTokens(accessToken, refreshToken)`; helpers build a `Cookie` header, read a `Set-Cookie`, and read the `sid` claim out of a token | `TestLogins` |
| **Real races** | `Concurrently.run(n, call)`: N threads, released together by a latch, every result returned | `Concurrently` |
| **"Indistinguishable" assertions** | Compare two error bodies without `timestamp`, `correlationId`, `instance` | `ProblemBodies` |
| **18 Tier-1 tests** | Session revocation and IDOR (3), refresh rotation, reuse, superseded, the race, the dead-session rollback (4), reset and change revocation, single-use reset tokens and their race, wrong current password (5), lockout and enumeration (3), `prod` without a key (3) | `SessionRevocationIntegrationTest`, `RefreshIntegrationTest`, `PasswordSessionsIntegrationTest`, `LoginLockoutIntegrationTest`, `JwtKeyConfigTest` |

**Not built yet:** §3.1–§3.6. **Tests:** Tier 2 of the carried debt is owed to Phase 11 (list in `PHASE_3_REQUIREMENTS.md` §3.0, *Out*).

### Evidence

| | |
|---|---|
| **Verified by me (2026-10-13)** | Suite before §3.0: **83 tests, 12 s wall, 2 Postgres containers**; after: **101 tests, 14 s, 2 containers**. The three classes with races passed 5 repeated runs. All **14 mutations** caught, each planted alone in a scratch copy of the project (deleted afterwards; your `src/main` was never edited). Boot 3.5.16's client detection order (Apache → Jetty → Reactor → JDK → simple) and that the JDK builder configures no cookie handler, read in `spring-boot-3.5.16-sources.jar`. |
| **Verified for the brief (2026-10-13)** | V7–V9 on a scratch copy of the dev database (dropped afterwards); the Hibernate 6.6.53 and Spring 6.2.19 behaviour listed in the brief's D4 and §3.1. |
| **Reported by you** | Nothing yet for Phase 3. |
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

---

## 5. Interview questions

**Answerable now**
1. **How do you test something that depends on time without sleeping?** Inject a `Clock` everywhere, and in tests replace it with one I can move. Mine is real time plus an offset, so tests that don't care see real time, and tests that need "past the lockout" move it 15 minutes.
2. **How do you test a race condition?** Real database, N threads held at a latch and released together, then assert counts: exactly one winner, everyone else the expected rejection, no 500. Never which thread won.
3. **How do you know your tests actually test something?** I plant the bug each test is about, one at a time, and the test must go red. In §3.0 all fourteen planted bugs were caught, each with a readable assertion.
4. **Why did your Spring test suite stay at two containers after adding five test classes?** Every class uses the exact same test configuration, so Spring reuses one cached context. Test-only beans (the clock, the email inbox) are declared in the shared configuration, not per class.
5. **How do you prove a login error doesn't leak whether an email exists?** Compare the whole error bodies for an unknown email, a wrong password and a locked account, ignoring only timestamp, correlation id and path. They must be equal.
6. **How do you test that the app refuses to start without a secret?** `ApplicationContextRunner` with just the configuration under test and the profile set, then assert the context failed and the message names the missing settings. Plus a test with a profile nobody listed, to prove it's an allow-list.

**Not answerable yet:** everything about tenancy (§3.1 onward).

---

## 6. Commands

```bash
./mvnw test                                                      # everything: 101 tests, ~14 s, 2 containers
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

---

## 8. Debt and what's next

- **Tier-2 tests** (Phase 11): §1.3 registration (duplicate 409 under a race, password never echoed), §1.4 verification details, §2.1 claim contents, cookie attributes and `no-store`, §2.2 `alg: none` / wrong key / **wrong `aud`** / converter rejections / the expiry boundary, §2.5 `CorsProperties` and the preflight table (needs a client that sends `Origin`: the JDK one does), Phase 0's `rejectedValue` test.
- **Test cleanup order** changes in §3.1: delete organizations before users (the owner invariant refuses to delete an owner). `TaskflowSecurityIntegrationTest` and the four new classes delete only users today.
- **Commit §3.0** (test files and docs, staged by name).
- **Your 15-minute notes** for Phase 1 and Phase 2.
