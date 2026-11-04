# Source map (Spring, security, testing pages)

> Which source section each knowledge page was built from. The JPA pages have their own, lesson-by-lesson map: [jpa/SOURCES.md](jpa/SOURCES.md). The decisions index lists its records in its own header. Built 2026-10-27; no phase document was changed.

## Spring pages

| Source | Content | Now in |
|---|---|---|
| P0 log §2 *Environment and tooling* | Initializr / Boot 4 starters · JDK 21 · Lombok annotation processing under JDK 23+ | spring 01 |
| P0 log §2 *Code that compiled…* | `@Max` on a `String` · colon-defaults on secrets | spring 02 · spring 01 |
| P0 log §2 *Design problems* | `common` importing a feature → `ErrorCode` interface · domain vs protocol errors · correlation id with nothing to correlate (completion line) | spring 01 · spring 02 · spring 04 |
| P0 log §2 *Process* | `git commit -a` | spring 01 |
| P0 log §3 *Configuration & secrets*, *API & web layer*, *Errors*, *Logging*, *Build* | Typed config, no secret defaults, `.gitignore` first, profiles; DTOs, pagination; `ProblemDetail`; MDC, JSON logs; plugins | spring 01, 02, 04 |
| P0 log §4 §0.1–0.3, §0.6–0.8 | Auto-configuration, component scan · Compose `.env` vs `env_file`, `CMD-SHELL`, init on empty dir · profile merging, relaxed binding, env precedence, cross-field rules · `ResponseEntityExceptionHandler` · MDC, Boot structured logging, tracing | spring 01, 04, 02, 03 |
| P0 log §5 Q1, 12–18, 21, 23 | Auto-config · fail loudly · component scan · slow queries · filter vs interceptor · error contract · 500 leaks · `ResponseEntityExceptionHandler` · finding a request · JDK upgrade | spring pages' interview sections |
| P0 log §7, §8 | Startup-failure deliberate failures · Actuator info / git `simple` · leaks still open · `rejectedValue` · `--debug` report owed | spring 01, 04, 02 |
| P0 req §0.3, §0.6–§0.9 | Pool size, `--debug` · why entities don't cross the web layer · error handler requirements, `include-*: never` · correlation filter rules · Actuator exposure, probes, liveness vs DB, graceful shutdown, springdoc findings | spring 01, 02, 03, 04 |
| P1 log §3.3 (validation parts), §3.5 *smaller*, §3.8 *JSON names*, §3.10 | Custom constraints, `ValidationMessages.properties`, normalisation · compact-constructor accessor, constructor binding, `@Profile` · JSON names as a contract · IntelliJ/Maven tooling | spring 02, 01 |
| P1 log §3.11 | 200 vs 201, `@Size(min = 3)` on names, a 409 not naming its field, a hard-coded `localhost:3000` link | spring 02 (HTTP semantics, validation) |
| P2 log §3 *Keys and configuration*, *The principal* | Fail closed on secrets; compact-constructor validation; `toString` redaction · `@AuthenticationPrincipal(errorOnInvalidType)`; `common` can't import a feature; Boot's `JwtDecoder` condition | spring 01, 02 |
| P2 log §3 *Cookie endpoints*, *Revocation* | Rejections that set headers are built in the controller · trailing slash · fixed `ORDER BY` | spring 02 |
| P2 ref Part F | `Converter` beans in MVC · `ProblemDetail` mixin · `ResponseEntity<ProblemDetail>` media type · trailing slash | spring 01, 02 |
| `JWT_AND_SESSIONS_GUIDE.md` §9 | `spring.profiles.active: dev` default and the allow-list | spring 01 |
| P3 req §3.1 (gate brief, traps), *Built*, review fix | Interceptor vs filter vs `AuthorizationManager` · patterns · fail closed · `OPTIONS` / `handleNoMatch` · slices load the gate | spring 03 |
| P3 req D5, §3.1 DF5 | Request scope vs `ThreadLocal`; `ScopeNotActiveException` with `@Async` | spring 03 |
| P3 req §3.2 (choices, traps), ref Part F | Role as a `String` + `@Pattern`; `handleHttpMessageNotReadable`; `/members/me` vs `{userId}` | spring 02 |
| P3 req §3.3 (brief) | `@EnableConfigurationProperties` registration | spring 01 |
| P3 ref Part F | `applyPreHandle` inside the `try`; `handleMatch` stores path variables; `isHandler` predicate; `@WebMvcTest` includes interceptors | spring 03 |

## Security pages

| Source | Content | Now in |
|---|---|---|
| P1 log §3.1 | Filter order, 401 vs 403, patterns checked per request, deny by default, `permitAll` vs `anonymous`, health details, `/error`, CSRF, `LogoutFilter`, Boot defaults off | security 01 (health → spring 04) |
| P1 log §3.2 | The four parts, provider order, one provider bean, events, `UserDetails` defaults, `getUsername`, `ROLE_`, re-authentication through the manager | security 02 |
| P1 log §3.3 | bcrypt, `{bcrypt}` prefix, 72 bytes, rules, records' `toString` | security 02 · security 05 |
| P1 log §3.4 | Six token properties, SHA-256 vs bcrypt, single use, fragment, one live link, lifetimes | security 03 |
| P1 log §3.6 | Which endpoints may reveal accounts, timing, lockout under pressure, lockout as DoS | security 05 |
| P1 log §3.8 | Logs carry ids; the client's IP | security 05, spring 04 |
| P1 log §4, §5 Q1–32, 40–54 | Practices; interview answers | security 01–05 |
| P1 decisions 1–27 | Basic, email login, principal, roles, events table, 409 at registration, tokens, lockout, reset/change rules | security 01–05 (+ decisions index) |
| P1 ref Part A, F | Rules A1–A7, A11–A14; provider and filter facts | security 01–05 |
| P2 log §3 *Tokens*, *Rotation*, *Revocation*, *Keys*, *Cookies*, *Resource server*, *Principal* | JWT facts, rotation/reuse/grace, one-row revocation, keys, cookies and tools, resource-server facts, principal readers | security 04, 02, 05 |
| P2 log §3 *Cookie endpoints*, *CORS* | `SameSite` + `Origin`, CORS in the chain, first registration wins, startup validation, CORS 403 | security 06 |
| P2 log §4, §5, §7 | Practices, interview answers, deliberate failures seen | security 04, 06 |
| P2 decisions D1–D8, D15 | Resource server, RS256, lifetimes, cookie, session check, reuse, password events, session list, CORS 403 | security 04, 06 |
| `JWT_AND_SESSIONS_GUIDE.md` §1–6, §9–10 | JWT anatomy, signed not encrypted, two tokens, hybrid revocation, rotation, keys, principles, traps | security 04 |
| P2 ref Part A, F | Rules A1–A5, A11–A12; resource-server and CORS facts | security 04, 06, 01 |
| P3 req D2–D8, §3.1–§3.2 | Tenancy decisions, the gate, 404 vs 403, roles rule, transfer, sudo mode | security 07, 02 |
| P3 req §3.3 (brief) | Two keys for invitations, role limits, expiry → 404, `ALREADY_MEMBER` oracle, accept transaction, invitee endpoints outside the gate | security 07, 05 |
| P3 log §3 *Sudo mode*, *One rule*, *The tenant gate*, *Proving "no oracle"*, *What a failed login must leave behind* | Sudo mode order; two comparisons; gate behaviour; whole-body comparison; lockout history | security 02, 07, 05 |
| P3 ref A1–A5, A11, A12, Part G | Isolation rules; sudo mode; §3.2 re-review nits (`transferOwnership` public, transfer-password lockout) | security 07, 02 |

## Testing pages

| Source | Content | Now in |
|---|---|---|
| `TESTING_GUIDE.md` §1–2, §4–5, §6.4, §7 | Libraries, four kinds of test, pyramid, profiles in tests, context caching (3 → 2) | testing 01 |
| `SECURITY_TESTING_GUIDE.md` §1–7 | What a security test proves, slice vs integration, the security slice (404 = let through), actuator and credential traps, post-processors, boundary pairs, table-driven tests, integration half, mutation table | testing 01, 02 |
| `SECURITY_TESTING_GUIDE.md` §8 | Tools (`AdjustableClock`, `TestLogins`, `Concurrently`, `ProblemBodies`, `ApplicationContextRunner`) and patterns | testing 02, 01 |
| P1 log §3.9 | Slice limits, `user()` vs real credentials, weakest role, test config in `src/test`, same mistake / different statuses, fixed clock, clearing `SecurityContextHolder` | testing 01, 02 |
| P1 log §7 (mutation table), P2 log §7, P3 req §3.0–§3.2 evidence | Mutation totals and the scratch-copy process; deliberate failures | testing 02 |
| P3 log §3 *Testing time*, *cookies and HTTP clients*, *races*, *startup failures*, *what only a race would show* | Ticking clock; JDK client without cookie jar; latch races; `ApplicationContextRunner` and allow-lists; stale-value tests | testing 02, 01 |
| P3 req §3.1 traps | Slices load the gate; `@WithMockUser` meets the gate | testing 01 |
| P3 req §3.2 *Built*, P3 log Evidence (2026-10-20) | Race test repeated; survivor mutation 13; Postman run without recorded rows | testing 02, 01 |
| P2 log §3 *Cookie endpoints* (Postman), P2 log §7 (scratch copy) | Postman cookie jar off, explicit `Cookie:` header, terminal for concurrency; experiments on a scratch copy | testing 01, spring 04 |
