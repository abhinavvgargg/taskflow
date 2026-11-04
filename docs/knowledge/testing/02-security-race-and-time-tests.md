# Testing 2. Security rules, races, time, and proving tests can fail

> The patterns behind TaskFlow's security and concurrency tests, and the mutation-testing habit that keeps them honest. Tools live in `src/test/java/com/abhinav/taskflow/`. Sources: [../SOURCES.md](../SOURCES.md).

## What a security test proves

A security test proves a **rule**, not a feature: did this caller, with this method and path, get through? It checks one of three outcomes: 401 (unidentified), 403 (identified, refused), or let through. Request matchers are checked lazily, one request at a time, so only a request can prove a rule ([security 01](../security/01-filter-chain-and-access-rules.md)).

## Boundary pairs, as a table

- **For every rule, the caller who should pass *and* the nearest caller who shouldn't.** A rule that lets everyone through also passes the "should pass" test. Examples: anonymous POST to `/auth/login` passes, the same path with GET → 401, `POST /auth/other` → 401; `denyAll` → 401 anonymous, 403 for `USER` **and** `ADMIN`.
- **The access table is a `@ParameterizedTest` with `@CsvSource`,** one row per request (caller, method, path, status), built with `MockMvcRequestBuilders.request(method, path)`. A new rule is a new row; a failure names the row. Phase 3's 22-row membership matrix (caller, action, target, role, status, code) is the same idea over HTTP.
- **Test as the weakest role that should succeed.** *What happened to us (Phase 1):* the first feature tests ran as admin, so a rule wrongly requiring admin would have locked out every user and the tests would still pass.
- **`user("u")` skips authentication** (it tests authorization only); real credentials go through the real check in integration tests.
- **Test the effect, not the configuration:** "`/error` is permitted" is proven by a non-empty JSON 401 body; "stateless" by no `Set-Cookie`; "correlation id on rejects" by the header on a 401.

## "No oracle" and "nothing happened"

- **Compare whole error bodies minus what differs on every request** (`ProblemBodies.withoutRequestSpecificFields` drops `timestamp`, `correlationId`, `instance`): status alone misses a different `detail` (*seen*: §3.0 mutation 10, §3.1 mutation 12).
- **Every "nothing happened" needs a positive pair:** "no email for an unknown address" means something only next to "an email for a known one" (`CapturingEmailSender`).
- **Assert the database, not only the response:** a 401 says the request failed; only the rows say what it left behind (revoked session, consumed token, event count, unchanged roles, `failed_login_attempts`).
- **Two-sided assertions:** "the other device is out" **and** "this one still works" (§3.0 mutation 7 showed the second half is needed).

## Time

- **`AdjustableClock`** (real time + offset, `@Primary` in the shared config): `reset()` in `@BeforeEach`; `advance(...)` is the moment "time passes". A ticking clock leaves tests that don't move it on real time; a fixed clock would freeze every test.
- **Boundaries need the right ordering:** reuse detection = rotate, `advance(11 s)`, present the old token; "superseded" needs the token **old when consumed** (`advance(30 s)` before the first refresh) or grace-from-creation and grace-from-consumption can't be told apart (§3.0 mutation 3a).
- **The decoder uses the same clock:** advancing past 15 minutes (or 7 days for invitations) expires every access token obtained before; log in again after moving it.
- **Queries must take `:now` from the `Clock`,** or the test clock can't reach them ([jpa 04](../jpa/04-queries-projections-and-paging.md)).
- **Unit tests use a fixed `Clock`** and test the exact boundary instant ("unlocked exactly at `locked_until`").

## Logins, devices and cookies

- **`TestLogins.login(rt, user)` returns `LoginTokens(accessToken, refreshToken)`;** two logins = two devices. Name variables after devices (`laptop`, `phone`) so assertions read as a story.
- **The test client has no cookie jar** (*verified*: Boot 3.5 picks the JDK `HttpClient` when no Apache/Jetty/Reactor client is present, with no cookie handler). Send `Cookie:` yourself (`TestLogins.cookieHeader`); that's what lets a test present an **old** token. `refreshSetCookie` empty = cookie left alone; `Max-Age=0` = cleared.
- **`TestLogins.sessionIdOf(token)`** reads the `sid` claim without the key (a JWT is readable) to find the session row.
- **Clear `SecurityContextHolder` after unit tests that set it** (thread-local).

## Races

- **Make requests overlap:** `Concurrently.run(n, call)` holds N threads on a latch and releases them together; twenty threads started in a loop often never overlap.
- **Assert counts, never which thread won;** "no 500" is part of the contract (`filteredOn(s -> s == 401).hasSize(19)` also proves no 409/500). Accept every correct refusal (two transfers: 409 or 403).
- **Run race tests repeatedly** before trusting them (§3.0: 5 runs; §3.2: 6).
- **Guards that matter only under a race get a direct test** with the stale value the race would produce, and test data where that guard is the **only** thing excluding the row ([jpa 05](../jpa/05-bulk-updates-and-concurrency.md)).

## Proving a test can fail: mutation checks

A test only counts if it can fail. For each test, plant the bug it guards against and confirm it turns red:
1. **In a scratch copy** of the project (`rsync` without `target`, `.git`, `.idea`; run with `-Dmaven.gitcommitid.skip=true`), never the working tree; delete it afterwards.
2. **One mutation at a time;** run the relevant test classes; record **which test failed and its assertion** ("expected 401, was 200").
3. **A survivor means a weak test,** not a strong one: strengthen the test, rerun. *What happened to us:* §3.2 mutation 13 survived until the outsider in the test data was a plain member instead of an owner.
4. **Results go in the sub-section's evidence table** in the requirements doc.

Totals so far: Phase 1 §1.1–1.2: 15/15 · Phase 2: none (its tests were deferred) · Phase 3 §3.0: 14/14 · §3.1: 15/15 · §3.2: 15/15.

**Deliberate failures** (from each brief) are the learning version of the same idea: break something on purpose and watch the symptom (a 500, a 409 at commit, a leak). They're run before or alongside the tests and recorded with what was actually seen, including when the prediction was wrong (§3.1: the 409 had no constraint name).

## Interview questions

1. **How do you test security rules?** A pair per rule (should pass, nearest should fail), as a table-driven test, URL rules in a slice, actuator and real credentials in integration, as the weakest role.
2. **How do you test a race condition?** Real database, N threads released together, assert counts and "no 500".
3. **How do you test time without sleeping?** An injected clock that tests can move; queries take "now" from it.
4. **How do you know your tests test something?** Mutation checks: plant each bug in a scratch copy; a surviving mutation means the test needs strengthening.
5. **How do you prove an error response doesn't leak whether something exists?** Compare whole bodies minus request-specific fields.
