# TaskFlow knowledge base

> What this project has taught, **organised by topic instead of by phase**, matching the three things it's for: implementing features with Spring Boot, using JPA effectively, and making production-grade design decisions. Built 2026-10-27 from Phases 0–3 (requirements, learning logs, implementation references, guides, and the §3.3 brief).
>
> **The phase documents are unchanged** and remain the record: each phase's brief, evidence, decisions table and rebuild recipe. These pages are the topic view across them; [SOURCES.md](SOURCES.md) and [jpa/SOURCES.md](jpa/SOURCES.md) map every source section to its page here.
>
> Labels used throughout: *seen* = observed in this project · *verified* = read in the framework source · *inferred* / *predicted* = reasoned, not run · *what happened to us* / *hit* = a real bug or near-miss here.

## Pages

| Area | Page | Read it when… |
|---|---|---|
| **Decisions** | [decisions/INDEX.md](decisions/INDEX.md) | you want "why did we do X?": every decision of every phase by topic, plus the cross-phase design rules |
| **Features** | [features/INDEX.md](features/INDEX.md) | you want to rebuild a feature: where its recipe, brief and mechanisms are |
| **Spring Boot** | [1. Boot, configuration, profiles and beans](spring/01-boot-configuration-and-beans.md) | wiring, `@ConfigurationProperties`, profiles, secrets, proxies, tooling |
| | [2. The web layer: contracts, validation, errors](spring/02-web-layer-validation-and-errors.md) | writing a controller, a request record, an error |
| | [3. Filters, interceptors and request scope](spring/03-filters-interceptors-and-request-scope.md) | running code around requests; per-request state |
| | [4. Observability and operations](spring/04-observability-and-operations.md) | logging, Actuator, probes, Compose |
| **Security** | [1. The filter chain and access rules](security/01-filter-chain-and-access-rules.md) | changing `TaskflowSecurityConfig` |
| | [2. Password authentication and the principal](security/02-password-authentication.md) | anything that checks a password or reads the caller |
| | [3. Tokens in email links](security/03-email-link-tokens.md) | verification/reset links, single-use secrets |
| | [4. JWT, refresh tokens and sessions](security/04-jwt-refresh-and-sessions.md) | bearer auth, refresh, logout, revocation |
| | [5. Account protection, "no oracle", privacy](security/05-account-protection-and-privacy.md) | enumeration, lockout, reset/change, IDOR, logs |
| | [6. CORS and CSRF](security/06-cors-and-csrf.md) | cookie endpoints, browser clients |
| | [7. Tenancy and organization authorization](security/07-tenancy-and-org-authorization.md) | anything under `/organizations/{orgId}` |
| **JPA** | [1. Entities and mapping](jpa/01-entities-and-mapping.md) | writing an entity |
| | [2. The persistence context](jpa/02-persistence-context-and-flushing.md) | a write didn't happen, happened twice, or was undone |
| | [3. Transactions](jpa/03-transactions.md) | choosing boundaries, propagation, side effects |
| | [4. Queries, projections, paging, sorting](jpa/04-queries-projections-and-paging.md) | a repository method or a list endpoint |
| | [5. Changing rows safely under concurrency](jpa/05-bulk-updates-and-concurrency.md) | two requests can touch the same row |
| | [6. Schema, constraints, invariants](jpa/06-schema-constraints-and-invariants.md) | a migration, or what the database should enforce |
| | [7. Auditing and time](jpa/07-auditing-and-time.md) | `created_by`, `updated_at`, `Instant`, the `Clock` |
| | [8. Multi-tenancy with Hibernate](jpa/08-multi-tenancy-with-hibernate.md) | §3.4 (`@TenantId`), tenant isolation |
| | [9. Testing persistence code](jpa/09-testing-jpa.md) | a `@DataJpaTest` or a database-touching integration test |
| **Testing** | [1. Strategy, slices and context caching](testing/01-test-strategy-and-slices.md) | choosing the kind of test; slice traps |
| | [2. Security rules, races, time, mutation checks](testing/02-security-race-and-time-tests.md) | proving a rule, a race, a deadline; proving a test can fail |

## If you read nothing else

**Spring Boot**
1. Fail at startup, not at 3am: typed, validated configuration; no defaults for secrets; allow-lists for anything dangerous.
2. `common` never imports a feature: shared code declares interfaces, features implement them.
3. Run code at the latest point that has what it needs: a filter for the correlation id, an interceptor for the tenant gate; fail closed when an input is missing.
4. One error format for everything, including errors from the security filter chain; one mistake, one answer.

**JPA**
5. Know when the database does something: `save` isn't a write, flush isn't a commit, a deferred constraint fails after your method returned.
6. Hibernate writes the whole row from what it loaded; rows changed concurrently are insert-only and changed by conditional statements whose row count is the decision.
7. Bulk statements bypass the persistence context and auditing: re-read afterwards, set audit columns yourself.
8. Transactions live in services, in other beans than side effects; slow or irreversible work happens outside them.

**Production design**
9. Judge every design by the attacker's copy and by what a member of A gets with B's ids: the same 404 for every miss.
10. Let the database enforce what code could forget: named constraints, partial unique indexes, triggers.
11. A test only counts if it can fail: plant the bug in a scratch copy, and a surviving mutation means a weak test.

## Where each area goes next (from the 12-week plan)

- **Spring:** method security and a custom `PermissionEvaluator` (Phase 4), strategy beans and `BeanPostProcessor` (Phase 6), AOP and `@Audited` (Phase 7), events, `@Async` with security context, `@Scheduled` (Phase 9), a second filter chain and an idempotency interceptor (Phase 10).
- **JPA:** composite FKs (Phase 4); inheritance, `equals`/`hashCode`, `@Version` and pessimistic locks (Phase 5); entity listeners, soft delete (Phase 7); Specifications and the measured N+1 hunt (Phase 8).
- **Security:** per-project roles and permissions (Phase 4), API keys and rate limiting (Phase 10), trusted proxies (Phase 11).
- **Testing:** Tier-2 test debt, `@WithMockProjectMember`, k6 load test (Phases 11–12).

## Keeping it current

These pages are written so they can be maintained: a new lesson goes into the page for its topic with a "Seen in §N.x" note, a new decision gets a row in the index. Your working agreement (`PROJECT_CONTEXT.md` §3) still says wrap-ups update the phase learning log; it wasn't changed, so until you decide otherwise these pages are updated when you ask.
