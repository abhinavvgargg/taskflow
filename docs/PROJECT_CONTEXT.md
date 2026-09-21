# TaskFlow — Project Context & Working Agreement

> Hand this file to a new chat before starting development. It contains everything agreed in the planning conversation (Sept 2026). Keep it in the repo at `docs/PROJECT_CONTEXT.md` and update it as decisions change.

---

## 1. Who I am and what I'm optimising for

- Java backend developer, preparing for a **job switch**, studying alongside a full-time job.
- Already studied: Spring Core, Spring Boot, JPA, Spring Security, REST APIs. Microservices covered through service discovery/registration (tutorials + sample code).
- Separate microservices track lives in `~/IdeaProjects/eazybank_microservices` — Kafka / Spring Authorization Server / Redis belong **there**, not in this project.
- Parallel prep tracks: DSA (1–2 problems daily, highest priority — it gates interviews), core Java depth, SQL, basic system design.
- Time budget: **~14 hrs/week** → project 6–7h, DSA 4h, core Java/theory 2–3h, notes/mock 1h.
- Honest self-assessment: I know *what* things are, but haven't used the deeper parts myself. My gap is hands-on judgement and confidence, not vocabulary.

---

## 2. The project

**TaskFlow — Multi-Tenant Project & Issue Tracking REST API**

Deliberately **not** a CRUD app. The value is in the engineering problems: per-project authorization, multi-tenancy, concurrent edits, a configurable workflow engine, audit history, and query performance.

**Stack:** Java 21, Spring Boot 3, Spring Security 6, Spring Data JPA / Hibernate, PostgreSQL, Flyway, JUnit 5 + Testcontainers, Docker + Compose, GitHub Actions, springdoc-openapi, Actuator + Micrometer.

**Domain shape:** Organizations (tenants) → Projects → Issues (Epic / Story / Task / Bug → Sub-tasks), with per-project membership and roles, comments, watchers, change history, configurable workflows, and search.

### 2.1 Full feature list

Legend: ✅ in the 12-week scope · 🔶 Phase 10, committed but sequenced after week 12 · ⏸️ deferred (see section 6).

**Identity & access**
- ✅ Registration with email verification (token-based, expiring, single-use)
- ✅ Login with access + refresh tokens; refresh rotation, reuse detection, revocation
- ✅ Logout — single device and all devices
- ✅ Password reset flow; password change with re-authentication
- ✅ Account lockout after repeated failed logins, auto-unlock after cooldown
- ✅ User profile (timezone, basic preferences), login history
- 🔶 API keys for integrations — scoped, revocable, hashed at rest, last-used tracking

**Organizations (tenants)**
- ✅ Create organization; creator becomes Owner
- ✅ Invite members by email with a role; accept/decline; expiring invites (⏸️ actual invite emails — token returned in the response for now)
- ✅ Org-level roles: Owner, Admin, Member
- ✅ Member removal, role changes, ownership transfer
- ✅ Org settings (default workflow)
- ✅ Tenant data isolation enforced on every query
- ⏸️ Working days / SLA policy settings

**Projects**
- ✅ Create project with a unique key (`TF`, `PAY`) used for issue keys like `TF-101`
- ✅ Project membership and roles: Admin, Developer, Reporter, Viewer
- ✅ A user can hold different roles in different projects
- ✅ Project settings: workflow, enabled issue types, default assignee
- ✅ Archive and restore a project
- ⏸️ Project-level custom fields

**Issues**
- ✅ Issue types with different fields: Epic, Story (story points), Task, Bug (severity, steps to reproduce, environment)
- ✅ Hierarchy: Epic → Story/Task/Bug → Sub-task
- ✅ Fields: title, description, priority, assignee, reporter, labels, due date, estimate
- ✅ Assignment rule: only project members can be assigned
- ✅ Comments with edit/delete rules (authors edit their own; admins delete any)
- ✅ Watchers and @mentions
- ✅ Full field-level change history (who, when, from → to)
- ✅ Soft delete, trash view, restore
- ✅ Concurrent-edit protection (`@Version` → HTTP 409); duplicate-free issue key generation under load
- ⏸️ Issue linking (blocks / is-blocked-by / relates-to / duplicates)
- ⏸️ Bulk operations (bulk assign, bulk status change, bulk move)
- ⏸️ Attachments with type/size validation

**Workflow engine**
- ✅ Per-project configurable statuses and allowed transitions
- ✅ Transition guards: required permission, required fields, required conditions (a Bug needs a resolution to close; a Story needs points to enter a sprint)
- ✅ Post-transition actions: auto-assign, notify watchers, stamp resolution date
- ✅ Default workflow templates (Simple, Kanban, Bug) seeded at startup
- ✅ Invalid transitions rejected with clear, specific errors

**Search & reporting**
- ✅ Dynamic filtering on any combination of fields, with pagination and stable sorting
- ✅ Full-text-ish search on title and description
- ✅ Basic aggregates: issues by status / assignee / priority, overdue issues
- ⏸️ Saved filters (private/shared), dashboards, sprint & velocity reports, cycle/lead time, CSV export

**Notifications**
- ✅ In-app notifications: assigned to you, mentioned, status changed, comment on a watched issue
- ✅ Email sending with a dev (log-only) and prod (SMTP) implementation
- ⏸️ Per-user preferences per event type, digest/batch mode

**Sprints & SLA** — ⏸️ entirely deferred (sprint create/start/complete rules, backlog & board, velocity, burndown, SLA policies with business-hours calculation, pause in BLOCKED, breach detection and escalation). One representative `@Scheduled` job is still built in Phase 9 (e.g. auto-close stale issues / invite cleanup) so the scheduling concepts are covered.

**Platform & operations**
- ✅ Health checks, Micrometer metrics, structured JSON logs with correlation IDs
- ✅ OpenAPI docs, consistent error model, audit log of security-sensitive actions
- ✅ Docker + Compose, CI pipeline
- 🔶 Rate limiting per user and per API key, idempotent handling of unsafe operations, API versioning

---

## 3. Working agreement (how the new chat must operate)

**The build loop, per phase:**
1. Explain the concepts needed, briefly, with the *why*.
2. Give **requirements, not code** — what to build and the rules it must satisfy.
3. I implement it.
4. I share the code; review it like a senior dev: bugs, better approaches, edge cases, and the interview questions it invites.
5. If I'm stuck: **hints first**, full solution only if I really need it.

**Two standing asks, on every feature:**
- **Name every production-grade practice as we apply it**, and say what it prevents — don't silently do it right.
- **Flag every concept that tutorials/basic courses skip.** State the **requirement** (what forced us here) → the **why** (what it buys us) → then the how.

**Inline tags to use:**
| Tag | Meaning |
|---|---|
| 💡 **Concept** | A framework idea worth understanding properly, with the why |
| ⚠️ **Trap** | A common mistake with real consequences — often let me create the bug first and see it |
| 🔍 **Look inside** | Read the Spring source or turn on debug logging here |
| 🎯 **Interview question** | A question I'll be asked about this exact code — write the answer down now |
| 🏗️ **Design decision** | A trade-off with no single right answer; present options, I choose and justify |
| 📊 **Measure it** | Don't assume — count queries, time the request, compare before/after |

**Discipline rules:**
- Time-box each phase. At 150% of the estimate, ship what works and move on.
- No polishing: no refactoring for elegance, no chasing 100% coverage.
- Never skip DSA for the project.
- After each phase I write 15 min of notes: what I built, what confused me, what I learned. These become interview stories.
- Keep the README current from week 1.
- Small, meaningful commits per feature.

---

## 4. Production-grade standards — applied from Phase 0, not retrofitted

| Area | Standard |
|---|---|
| Layering | Controller (HTTP only) → Service (business logic + transactions) → Repository. No business logic in controllers. |
| DTOs | Separate request/response records. Entities never enter or leave the web layer. |
| Errors | One model: RFC 7807 `ProblemDetail` + error code + field errors. No stack traces leaked. Correct status codes. |
| Validation | Three layers on purpose: Bean Validation at the boundary, business rules in services, DB constraints as final guard. |
| Schema | Flyway migrations only. **Never** `ddl-auto=update`. Explicit constraints, FKs, indexes. |
| Transactions | Explicit boundaries in services, `readOnly=true` for queries, no remote calls inside transactions. |
| Security | Deny by default. Authorize in the **service** layer, not just controllers. Never trust a client-supplied ID without a permission/ownership check. |
| Config | Externalised, typed `@ConfigurationProperties`, validated at startup. Secrets from env vars. Profiles: dev / test / prod. |
| JPA | `spring.jpa.open-in-view=false` from day one. No N+1 in any endpoint. SQL log review per feature. |
| Logging | SLF4J parameterised messages, correlation IDs, no PII or tokens in logs. |
| Testing | Unit (business rules) + slice (`@WebMvcTest`, `@DataJpaTest`) + **Testcontainers** integration + a security test per permission rule. **No H2** — it lies about locking, filters and native SQL. |
| API | Consistent naming, pagination on every list, stable sort, versioning before breaking changes. |
| Ops | Actuator, Micrometer business metrics, graceful shutdown, non-root container user, health-check-based compose startup. |

---

## 5. The 12-week plan (agreed scope)

| Week | Phase | Contents | Key concepts |
|---|---|---|---|
| 0 (weekend) | Revision | Proxies/`@Transactional` in depth, JPA persistence context, Spring Security architecture page. Everything else just-in-time. | — |
| 1 | **0 — Foundations** | Boot 3 + Java 21 setup, package-by-feature, Postgres via Compose, Flyway, profiles, `BaseEntity` + JPA auditing, global `ProblemDetail` handler, pagination/response shapes, correlation-ID filter, structured logging, OpenAPI, Actuator, first Testcontainers test | Auto-configuration, typed config validation, filters vs interceptors, `open-in-view=false`, Testcontainers |
| 2 | **1 — Users & auth core** | User + roles, registration, BCrypt/delegating encoder, `UserDetailsService`, email verification tokens (hashed, expiring, single-use), password reset, failed-attempt lockout, login history | Filter chain end-to-end, `AuthenticationManager`/`Provider`, `SecurityContextHolder`, secure token design |
| 3 | **2 — JWT & sessions** | Own JWT filter, DB-backed refresh tokens with rotation + reuse detection, logout (one/all devices), revocation, JSON 401/403, CORS | `OncePerRequestFilter`, filter placement, stateless auth, 401 vs 403, token theft scenarios |
| 4 | **3 — Orgs & multi-tenancy** | Organization + membership, invitations (expiring tokens), role changes, ownership transfer, request-scoped tenant context, Hibernate tenant filter | Request-scoped beans + scoped proxies, `ThreadLocal` + `@Async` pitfall, `@Filter` vs `@Where`, tenancy strategies |
| 5–6 | **4 — Fine-grained authorization** 🔴 | Project + project membership roles, permission model, **custom `PermissionEvaluator`**, role hierarchy, `@PreAuthorize`/`@PostAuthorize`/`@PostFilter`, custom `@RequiresProjectPermission`, per-request permission cache | Method security internals, SpEL in security, `hasPermission`, authorities vs permissions, dynamic per-resource authz. **Highest interview value — do not rush.** |
| 7 | **5 — Issues & concurrency** | Issue inheritance (Epic/Story/Task/Bug), parent-child self-reference, labels, issue-key generation `TF-101` safe under concurrency, `@Version` → HTTP 409, comments with ownership rules | Inheritance strategies + query cost, self-references, optimistic vs pessimistic locking and *why each is used where*, entity `equals`/`hashCode` |
| 8 | **6 — Workflow engine** | Per-project statuses + allowed transitions, guards as strategy beans (permission / required fields / conditions), post-transition actions, seeded templates | Injecting `List<T>`/`Map<String,T>` of strategies, `BeanPostProcessor`, open/closed in Spring, `@Conditional` |
| | | ✅ **Resume-ready here — start applying at week 8** | |
| 9 | **7 + 8 — Audit & performance** | Field-level history via entity listeners, custom `@Audited` AOP, audit in `REQUIRES_NEW`, soft delete (`@SQLDelete` + filter) + trash/restore; Specifications search, projections, dashboard aggregates, deliberate N+1 hunt | `@EntityListeners`, AOP pointcuts, `REQUIRES_NEW` + its downsides, Criteria API, `@EntityGraph` vs `JOIN FETCH` vs `@BatchSize`, index design, 📊 measured before/after |
| 10 | **9 (trimmed) — Events & async** | Application events on issue changes, `@TransactionalEventListener(AFTER_COMMIT)`, `@Async` notifications, in-app notifications, dev vs prod email sender, one `@Scheduled` job | Phantom-notification trap, custom `TaskExecutor` with bounded queue, propagating security context to async threads, idempotent scheduled jobs |
| 11 | **12 — Packaging & docs** | Multi-stage Dockerfile (layered JAR), full `docker-compose.yml` (app + Postgres + MailHog), GitHub Actions CI, README with architecture + ER diagrams, **Key Design Decisions** doc, Postman collection, `.env.example` | Container packaging, reproducible builds, presenting a project in 2 minutes |
| 12 | **11 (trimmed) — Tests & observability** | Fill test gaps, custom `@WithMockProjectMember`, full-flow Testcontainers tests, Micrometer metrics, health indicators, k6 load test, security review | Test pyramid, slice vs full context + context caching, security testing |
| 13+ | **10 — API keys** ✅ committed | API keys with scopes (hashed, shown once), **second ordered `SecurityFilterChain`**, custom `AuthenticationProvider` + custom `Authentication`, token-bucket rate limiter (429 + `Retry-After`), `@Idempotent` interceptor, API versioning | Multiple filter chains, extending (not just configuring) Spring Security, `HandlerInterceptor` vs `Filter`, idempotency design |

**Must not be cut (the learning depends on them):** Phase 4 authorization · Phase 5 inheritance + both locking strategies · Phase 7 audit/AOP/`REQUIRES_NEW` · Phase 8 Specifications + measured N+1 fix · Phase 9 after-commit events + async security context · Phases 2–3 JWT rotation + tenant isolation · testing at every phase.

---

## 6. Deferred backlog — remind me to pick these up

**Committed, only sequenced last:** Phase 10 (above). I explicitly said this one gets built.

**One new concept each — worth an evening:**
- Bulk operations — `@Modifying` bulk JPQL, Hibernate batching, why bulk updates bypass the persistence context (~3h, best of this group)
- Attachments — multipart, real content-type validation, streaming (~3h)
- CSV export — `StreamingResponseBody`

**Pure repetition, zero learning loss** (build only to enrich the product): saved filters, dashboards/reports, issue links, custom fields, invitation emails, sprints/velocity/burndown, SLA policies with business-hours + pause + escalation, notification preferences and digest mode.

**Belongs to the microservices track, not here:** Kafka split of notifications, Spring Authorization Server, Redis caching and distributed locks/rate limiting.

For each deferred item I keep two lines of notes — what problem it solves, how I'd implement it *in this codebase*. Good interview answers. **Never list unbuilt work on the resume.**

---

## 7. Resume framing (for later)

Title it **"TaskFlow – Multi-Tenant Project & Issue Tracking Platform"**, never "Jira clone". Pick 4–5 bullets from: multi-tenancy, per-project authorization, JWT rotation + API-key auth, workflow engine, concurrency/optimistic locking, audit trail with `REQUIRES_NEW`, measured N+1 fix, testing + Docker/CI. **Only bullets for things actually built, and only numbers actually measured** (Hibernate statistics for query counts, k6 for p95, JaCoCo for coverage).

---

## 8. Reference resources

- Marco Behler: Spring Framework guide · Spring Boot autoconfiguration · **`@Transactional` in depth** · Spring Security guide
- Spring docs: Framework reference (Core, Data Access/Transactions) · Spring Security reference (**Servlet Architecture**, Authentication Architecture, Method Security) · Spring Data JPA reference
- Vlad Mihalcea's blog (relationship mapping, entity states, N+1, Open Session in View anti-pattern) · Thorben Janssen
- Books (optional): *Spring Start Here* · *Spring Security in Action 2e* · *High-Performance Java Persistence*
- ⚠️ Ignore anything using `WebSecurityConfigurerAdapter` or `antMatchers()` — removed in Spring Security 6.

---

## 9. First message for the new chat

> Read `docs/PROJECT_CONTEXT.md`. Follow the working agreement in section 3. Let's start **Phase 0** — give me the requirements, not the code.
