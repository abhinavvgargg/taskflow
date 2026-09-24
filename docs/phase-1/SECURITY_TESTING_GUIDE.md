# Testing Security Rules — Guide for §1.1 onward

> Companion to `../phase-0/TESTING_GUIDE.md` (the four kinds of test, context caching, the traps) and `PHASE_1_REQUIREMENTS.md` §1.1 (the access table these tests prove).
> Behaviour marked **verified** was observed on this project's code on 2026-09-24 (Boot 3.5.16, Spring Security 6.5). You write the tests. This guide gives you the tools, where each test belongs, and what each test must prove.

---

## 1. What a security test proves

A security test proves a **rule**, not a feature. It asks one question: *did this caller, with this method and path, get through the gate or not?* The controller's behaviour is somebody else's test.

So a security test only ever checks one of three outcomes:

| Outcome | Status | Meaning |
|---|---|---|
| **Unauthenticated** | 401 | "I don't know who you are." Anonymous caller or bad credentials |
| **Forbidden** | 403 | "I know who you are, and the answer is no." |
| **Let through** | anything except 401/403 | The chain passed the request on to MVC |

💡 **You've already had two bugs that no feature test caught.** The brace pattern and `EndpointRequest.to("health/**")` both let the app **start cleanly** and failed **per request**. That's what security tests are for: request matchers are checked lazily, one request at a time, so only a request can prove a rule works.

---

## 2. Where each test belongs: slice or integration

| Rule | Slice (`@WebMvcTest`) | Integration (`@SpringBootTest`) |
|---|---|---|
| `/api/v1/auth/*` POST permitted, method restriction, exact path list | ✅ | |
| `/api/v1/**` authenticated | ✅ | |
| `denyAll()` as the last rule | ✅ | |
| **Actuator rules** (health, info, metrics) | ❌ **gives wrong results** (see §2.2) | ✅ |
| **Real** credential checks (Basic header → your user store) | ❌ | ✅ |
| `/error` permitted, correlation ID on 401, no `Set-Cookie` | | ✅ |

### 2.1 The slice: a gate with nothing behind it

Your `TaskflowSecurityTests` uses `@WebMvcTest(TaskflowSecurityConfig.class)`. The annotation's value is the **list of controllers to load**, and you passed a config class instead. **Verified:** the context contains **no controllers at all**. That turns out to be a useful setup for testing rules:

- A request **let through** finds no handler → **404**.
- A request **blocked** → **401 / 403**.

So in this class, **404 means "let through"**. That stays true even after §1.3 adds `AuthController`, because this slice never loads it. Keep the setup, and **write a comment on the class saying so**. A reader seeing `@WebMvcTest(TaskflowSecurityConfig.class)` will assume it's a mistake. The comment should also say why 404 is the success status in these tests.

**Verified results with this setup:**

| Request | Status |
|---|---|
| anonymous `POST /api/v1/auth/register` | 404 → let through ✅ |
| anonymous `GET /api/v1/organizations` | 401 ✅ |
| `user("u")` `GET /api/v1/organizations` | 404 → let through ✅ |
| anonymous / `user` `GET /nope` | 401 / 403 ✅ |

### 2.2 ⚠️ Trap (verified): actuator rules silently never match in a slice

| Request | In the slice | In the real app |
|---|---|---|
| anonymous `GET /actuator/health` | **401** | 200 |
| `ADMIN` `GET /actuator/metrics` | **403** | 200 |

`@WebMvcTest` doesn't load the actuator infrastructure. Without it, `EndpointRequest` has no endpoint paths to match against. It **doesn't fail**: it just matches nothing. Every actuator request falls through to `denyAll()`. Write actuator tests in a slice and you'd either "prove" the wrong behaviour, or change correct rules to make the tests pass. **Actuator rows go in the integration test.**

### 2.3 ⚠️ Trap (verified): real Basic credentials fail in the slice

`with(httpBasic("test-user", "test-password"))` in the slice returns **401** even with the right password. The slice has no `@ActiveProfiles("test")`, so it runs as **`dev`** (Phase 0 testing guide §6.4). Under `dev` there's no `test-user`, only Boot's generated password. You could add the profile, but the better move is to **not test real credentials in the slice at all**: that's the integration test's job. The slice uses `with(user(…))`, see §3.

---

## 3. The tools (`spring-security-test`)

### Post-processors, one per request. **Use these in security tests.**

| Call | What it does |
|---|---|
| `.with(anonymous())` | Calls as an anonymous user. Worth writing even though it's the default: the test then *says* who's calling. |
| `.with(user("u"))` | Places an **already authenticated** user in the context, role `USER`. |
| `.with(user("a").roles("ADMIN"))` | Same, with role `ADMIN`. `roles()` adds the `ROLE_` prefix for you; `authorities()` doesn't. |
| `.with(httpBasic("u", "p"))` | Sends a **real** `Authorization: Basic` header through `BasicAuthenticationFilter` and your user store. |

💡 **`user()` skips authentication completely.** It never checks a password; it puts a finished `Authentication` straight into the context. So it tests **authorization** ("is a USER allowed here?") and says **nothing** about **authentication** ("does a correct password work?"). You need both kinds of test, and they belong in different places: `user()` in the slice, `httpBasic()` or `withBasicAuth()` against the real server.

### Annotations: fine for feature tests, avoid in security tests

`@WithMockUser`, `@WithAnonymousUser`, and `@WithUserDetails` (§1.2, loads a real principal from your `UserDetailsService`).

⚠️ **Don't put a class-level `@WithMockUser` on the security test class.** It applies to **every** test, including the ones meant to be anonymous, unless each is overridden. The test then asserts 401 and gets 404, or worse, you "fix" the expected status. In a security test, **each request states its caller**.

---

## 4. What to test: boundary pairs

For every rule, test **the caller who should pass *and* the nearest caller who shouldn't**. One test per rule isn't enough. A rule that lets **everyone** through also passes the "should pass" test.

| Rule | Must pass | Nearest must-fail |
|---|---|---|
| Auth endpoints permitted | anonymous POST → through | the **same path with GET** → 401 (method restriction) · `POST /api/v1/auth/other` → 401 (the list is exact) |
| …and `permitAll`, not `anonymous` | a **logged-in user** POST → through | — (this is the bug you had) |
| `/api/v1/**` authenticated | `user` → through | anonymous → 401 |
| `denyAll` last | — | anonymous → 401 · **`user` → 403** · **`ADMIN` → 403** (`denyAll` means even admins) |
| Health / info permitted *(integration)* | anonymous → 200 · logged-in → 200 · `/health/liveness` → 200 | — |
| Metrics `ADMIN` *(integration, after §1.2)* | `ADMIN` → 200 | `USER` → **403** · anonymous → **401** |
| Health details `ADMIN` *(integration, after the fix)* | `ADMIN` sees `components` | `USER` gets **no** `components` |

### A pattern worth using: the table as a parameterised test

Your access table *is* data: caller, method, path, expected status. Write it as a JUnit `@ParameterizedTest` with `@CsvSource` (or `@MethodSource`), one row per request, and use `MockMvcRequestBuilders.request(HttpMethod, path)` to build any method from the data. The test then **reads like the access table in the requirements**, a new rule is a new row, and a failure names the row that broke.

Since `user(...)` isn't a string, the caller column needs a little mapping: e.g. `ANON` / `USER` / `ADMIN` → the matching post-processor. That mapping is the only helper code this needs.

---

## 5. The integration half

- **Share the context.** Copy `OrganizationApiIntegrationTest`'s configuration **exactly**: `RANDOM_PORT`, `@ActiveProfiles("test")`, `@Import(TestcontainersConfiguration.class)`. Any difference starts a second application and a second container (Phase 0 testing guide §7). 📊 Check the container count before and after.
- **Real credentials:** `restTemplate.withBasicAuth(…)` right → 200; wrong password → 401. This is the only place the password check itself gets tested in §1.1.
- **`/error` is permitted: test the effect, not the path.** An anonymous 401 from the real server has a JSON body with `"status":401`. That body is **produced by `/error`**. Remove the `/error` permit and the body comes back **empty**. So "401 body is non-empty JSON" is the test.
- **Correlation ID on a rejected request:** a 401 carries `X-Correlation-Id`. That's the Phase 0 filter-ordering decision, as a regression test.
- **Stateless:** no response carries `Set-Cookie`.

---

## 6. Mutation checks: every one must turn a test red

Plant each bug in turn, run the tests, confirm at least one fails, then revert. A rule with no failing test isn't tested.

| Plant this | Caught by |
|---|---|
| `permitAll()` → `anonymous()` on the auth endpoints | logged-in POST → through |
| `denyAll()` → `authenticated()` | `user` `GET /nope` → 403 |
| Drop `HttpMethod.POST` from the auth rule | anonymous GET on an auth path → 401 |
| Paths back into the brace pattern | any anonymous POST (it becomes a 500) |
| `EndpointRequest.to("health/**", …)` | integration: anonymous health → 200 |
| Delete the `/error` permit | integration: 401 body non-empty |
| Delete the CSRF disable | any POST → 403 |
| `/api/v1/**` → `hasRole("ADMIN")` | `user` → through (this is why feature tests run as `USER`) |

---

## 7. Housekeeping

- **Move the class** from the root package to `com.abhinav.taskflow.common.security`, next to the config it tests. Test packages mirror main packages.
- **Name each test after the rule** (`authEndpoints_getIsRejected_evenAnonymously`), not the mechanism (`rejectsMissingCredentials`). When it fails in CI, the name alone should tell you which rule broke.
- **Change the tests with the rules.** §1.2 adds real users (so the `ADMIN` rows become testable in integration), and each new endpoint adds a row. That's why the table layout pays off.
