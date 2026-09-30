# TaskFlow — Multi-Tenant Project & Issue Tracking Platform

A REST API for project and issue tracking, built as a deliberate exercise in the problems that make backend systems hard: per-project authorization, multi-tenancy, concurrent edits, a configurable workflow engine, audit history, and query performance.

> **Status — Phase 1 (users & authentication) complete.** Registration with email verification, login, account lockout, password reset and change, and a security event log are built. Requests authenticate with **HTTP Basic until Phase 2 replaces it with JWT**. Multi-tenancy, projects, issues and the workflow engine are on the [roadmap](#roadmap) and are **not built yet**.

---

## Tech stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.16 (Spring Framework 6.2) |
| Security | Spring Security 6.5 · bcrypt (`DelegatingPasswordEncoder`) |
| Persistence | Spring Data JPA · Hibernate 6 · PostgreSQL 17 |
| Migrations | Flyway |
| Testing | JUnit 5 · AssertJ · Mockito · MockMvc · Testcontainers |
| Observability | Spring Boot Actuator · Micrometer · structured JSON logs (ECS) |
| Build & runtime | Maven (wrapper included) · Docker Compose |

---

## Quick start

**Prerequisites:** JDK 21 and Docker. Maven is not required — use the bundled `./mvnw`.

```bash
git clone https://github.com/abhinavvgargg/taskflow.git
cd taskflow
cp .env.example .env
```

Edit `.env` and set `POSTGRES_PASSWORD=taskflow_local_dev` — the `dev` profile connects with that password.

```bash
docker compose up -d        # start Postgres; wait until STATUS shows "healthy"
docker compose ps
./mvnw spring-boot:run      # starts on http://localhost:8080 with the dev profile
```

Flyway creates the schema on first start. Check it's up:

```bash
curl -s localhost:8080/actuator/health
```

### Create an account and sign in (dev)

In the `dev` profile, emails aren't sent: they're **written to the application log**. That includes the verification link, which carries the token after `#token=`.

```bash
curl -s -i -H 'Content-Type: application/json' \
  -d '{"email":"carol@example.com","username":"carol","displayName":"Carol","password":"carol-password-1"}' \
  localhost:8080/api/v1/auth/register
# the log shows: Email sent to carol@example.com, with subject Verify your email address, and body http://localhost:3000/verify-email#token=<TOKEN>
curl -s -i -H 'Content-Type: application/json' -d '{"token":"<TOKEN>"}' localhost:8080/api/v1/auth/verify-email
curl -s -u carol@example.com:carol-password-1 localhost:8080/api/v1/organizations
```

The first admin is made by hand: `update user_accounts set role = 'ADMIN' where username = '<username>';`.

**Reset the local database** (deletes all data):

```bash
docker compose down -v && docker compose up -d
```

> Postgres only reads `POSTGRES_*` variables when its data directory is empty. If you change the password in `.env` after the first run, reset the volume for it to take effect.

---

## API

Base path: **`/api/v1`**. All requests and responses are JSON; errors are `application/problem+json` (one exception: see [Errors](#errors)).

### Authentication

**Every endpoint under `/api/v1` requires credentials, except the six `POST` endpoints under `/api/v1/auth`.** Until Phase 2, credentials are **HTTP Basic** (email + password) on every request: an API-client bridge, not something a browser should use. Sessions are never created.

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| `POST` | `/api/v1/auth/register` | `{email, username, displayName, password}` | **201** + the account | 400 · 409 `EMAIL_ALREADY_REGISTERED` / `USERNAME_TAKEN` |
| `POST` | `/api/v1/auth/verify-email` | `{token}` | **204** | 400 `INVALID_TOKEN` |
| `POST` | `/api/v1/auth/verify-email/resend` | `{email}` | **202**, always | 400 |
| `POST` | `/api/v1/auth/login` | `{email, password}` | **200** + the account | 401 `AUTHENTICATION_FAILED` · 403 `EMAIL_NOT_VERIFIED` |
| `POST` | `/api/v1/auth/password-reset/request` | `{email}` | **202**, always | 400 |
| `POST` | `/api/v1/auth/password-reset/confirm` | `{token, newPassword}` | **204** | 400 · 400 `INVALID_TOKEN` |
| `GET` | `/api/v1/users/me/login-history` | — | **200**, paginated, newest first | 401 |
| `PUT` | `/api/v1/users/me/password` | `{currentPassword, newPassword}` | **204** | 400 `PASSWORD_UNCHANGED` / `CURRENT_PASSWORD_INCORRECT` · 401 |

**Rules worth knowing:**
- **Passwords:** at least 12 characters and at most 72 UTF-8 bytes; no composition rules.
- **Emails and usernames** are case-insensitive: they're stored lowercase.
- **An account can't sign in until its email is verified.** A verification link lasts **24 hours**, a reset link **30 minutes**. Each works once, and requesting a new one invalidates the old one.
- **Nothing reveals whether an email is registered**, except registration itself (by design). An unknown email, a wrong password and a locked account get the same 401. Resend and reset requests always answer 202.
- **Lockout:** 5 wrong passwords, through `/auth/login` or Basic, lock the account for 15 minutes. A successful password reset unlocks it.
- **A password reset** also verifies an unverified email. **A password change** requires the current password (a wrong one counts toward lockout) and invalidates any outstanding reset link. Both email the owner.
- **Security events:** sign-ins, failures, lockouts, verification, resets and changes are recorded (append-only), with the client's socket IP. Your own sign-in history is at `/users/me/login-history`.

### Organizations

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/v1/organizations` | **201** + `Location` header, body = created organization | 400, 409 |
| `GET` | `/api/v1/organizations/{id}` | **200** | 404 |
| `GET` | `/api/v1/organizations` | **200**, paginated | 400 |

**Create**

```bash
curl -i -u carol@example.com:carol-password-1 -X POST localhost:8080/api/v1/organizations \
  -H 'Content-Type: application/json' \
  -d '{"name": "Acme Corp", "slug": "acme-corp"}'
```

| Field | Rules |
|---|---|
| `name` | required, 3–100 characters |
| `slug` | required, 3–63 characters, lowercase letters and digits in words separated by single hyphens (`acme-corp`, not `-acme`, `acme--corp` or `Acme`), **unique** |

**Response**

```json
{
  "id": 51,
  "name": "Acme Corp",
  "slug": "acme-corp",
  "createdBy": "system",
  "createdAt": "2026-09-23T10:15:30.123456Z"
}
```

### Pagination and sorting

```bash
curl -s -u carol@example.com:carol-password-1 'localhost:8080/api/v1/organizations?page=0&size=20&sort=name,asc'
```

| Parameter | Behaviour |
|---|---|
| `page` | **Zero-based.** Negative values are treated as `0`. |
| `size` | Default **20**, capped at **100**. Values below 1 fall back to the default. |
| `sort` | `field,asc` or `field,desc`. Allowed fields: `id`, `name`, `slug`, `createdAt`, `updatedAt`. Any other field returns **400**. Default: `createdAt,desc`. |

Every sort ends with `id` as a tiebreaker, so pages never repeat or skip rows.

**Response envelope**

```json
{
  "content": [ { "id": 51, "name": "Acme Corp", "slug": "acme-corp", "createdBy": "system", "createdAt": "..." } ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

---

## Errors

Every error from application code or Spring MVC uses [RFC 7807 Problem Details](https://www.rfc-editor.org/rfc/rfc7807) with a stable, machine-readable `code`.

> **Exception, until Phase 2:** a 401 or 403 produced by the security filter chain (missing or wrong Basic credentials, or a denied path) is Spring Boot's default error JSON (`timestamp`, `status`, `error`, `path`), with a `WWW-Authenticate: Basic` header on 401. It still carries `X-Correlation-Id`. Errors from `/auth/login` and the other endpoints are Problem Details.

```json
{
  "type": "about:blank",
  "title": "Duplicate slug",
  "status": 409,
  "detail": "Organization with slug acme-corp already exists",
  "instance": "/api/v1/organizations",
  "slug": "acme-corp",
  "code": "DUPLICATE_SLUG",
  "timestamp": "2026-09-23T10:15:30.456Z",
  "correlationId": "3a173e64fb474099881199d5e27a3223"
}
```

Validation failures add a `fieldErrors` array:

```json
{
  "status": 400,
  "code": "VALIDATION_FAILED",
  "fieldErrors": [
    { "field": "name", "message": "must not be blank" },
    { "field": "slug", "message": "must match \"^[a-z0-9]+(?:-[a-z0-9]+)*$\"" }
  ]
}
```

| Code | Status | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Request body or parameters fail validation |
| `INVALID_SORT_PROPERTY` | 400 | `sort` names a field that isn't allowed |
| `INVALID_TOKEN` | 400 | A verification or reset token is unknown, used, expired or revoked (one answer for all four) |
| `PASSWORD_UNCHANGED` | 400 | The new password equals the current one (`field: newPassword`) |
| `CURRENT_PASSWORD_INCORRECT` | 400 | Wrong current password on a change (`field: currentPassword`) |
| `AUTHENTICATION_FAILED` | 401 | Login failed: unknown email, wrong password or locked account (identical bodies) |
| `EMAIL_NOT_VERIFIED` | 403 | Correct password, but the email isn't verified yet |
| `BAD_REQUEST` | 400 | Malformed JSON or unreadable request |
| `ORGANIZATION_NOT_FOUND` | 404 | No organization with that id |
| `NOT_FOUND` | 404 | Unknown path |
| `METHOD_NOT_ALLOWED` | 405 | Unsupported HTTP method |
| `DUPLICATE_SLUG` | 409 | Slug already taken |
| `EMAIL_ALREADY_REGISTERED` | 409 | Registration with an email that has an account (`field: email`) |
| `USERNAME_TAKEN` | 409 | Registration with a taken username (`field: username`) |
| `RESOURCE_CONFLICT` | 409 | A database constraint rejected the write (e.g. a concurrent duplicate) |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | `Content-Type` isn't JSON |
| `INTERNAL_ERROR` | 500 | Anything unexpected — details are logged server-side, never returned |

Clients should branch on **`code`**, not on `title` or `detail`. Codes are part of the API contract; renaming one is a breaking change.

---

## Conventions

### API

- Plural nouns, no verbs in paths: `/organizations`, not `/getOrganization`.
- Versioned from day one under `/api/v1`.
- `POST` → **201** with a `Location` header; `DELETE` → **204**.
- Every list endpoint is paginated, size-capped, and stably sorted.
- Entities never cross the web layer — requests and responses are separate record DTOs.

### Database

- `snake_case`, **plural** table names, **singular** column names.
- Primary key `id` (`bigint`, from a pooled sequence); foreign keys `<table>_id`.
- Timestamps are `timestamptz`, stored in UTC.
- **Every constraint is explicitly named:** `pk_`, `uk_`, `fk_`, `ck_`, `ix_` + table + column(s), e.g. `uk_organizations_slug`. Constraint names are used to identify violations at the application layer.
- Schema changes go through **Flyway migrations only** (`src/main/resources/db/migration`, `V<n>__<description>.sql`). `ddl-auto` is `validate`, never `update`.
- **An applied migration is never edited.** Fix forward with a new version — Flyway's checksums will refuse to start otherwise.

---

## Configuration

### Profiles

| Profile | Used for | Notes |
|---|---|---|
| `dev` | Local development (**default**) | Local Postgres, readable console logs, SQL logging |
| `test` | Automated tests | Database supplied by Testcontainers |
| `prod` | Deployment | JSON logs, no SQL logging, all connection settings from environment variables |

Select a profile with `SPRING_PROFILES_ACTIVE`. Because `dev` is the default, **production deployments must set `SPRING_PROFILES_ACTIVE=prod` explicitly.**

### Production environment variables

| Variable | Purpose |
|---|---|
| `SPRING_PROFILES_ACTIVE` | Must be `prod` |
| `DB_HOST` | Database host |
| `DB_PORT` | Database port |
| `DB_NAME` | Database name |
| `DB_USER` | Database user |
| `DB_PASSWORD` | Database password |
| `FRONTEND_BASE_URL` | Base URL for links in emails (`{base}/verify-email#token=…`, `{base}/reset-password#token=…`) |

None of these have defaults. **A missing variable stops the application at startup** rather than letting it fall back to a guessed value.

> ⚠️ **`prod` doesn't start yet, on purpose.** There's no production email sender until Phase 9, and a deploy that silently dropped every verification and reset email would be worse than one that refuses to start.

### Application settings

| Property | Default | Validation |
|---|---|---|
| `taskflow.api.default-page-size` | 20 | ≥ 1, and ≤ `max-page-size` |
| `taskflow.api.max-page-size` | 100 | 1–100 |
| `taskflow.security.tokens.email-verification-ttl` | `24h` | positive |
| `taskflow.security.tokens.password-reset-ttl` | `30m` | positive |
| `taskflow.security.lockout.max-failed-attempts` | 5 | ≥ 1 |
| `taskflow.security.lockout.duration` | `15m` | positive |
| `taskflow.app.frontend-base-url` | `http://localhost:3000` in dev | required |

These are bound to a typed, validated `@ConfigurationProperties` record — an invalid value **fails startup** with a message naming the property.

---

## Observability

### Correlation IDs

Every request gets a correlation ID, returned in the **`X-Correlation-Id`** response header, attached to every log line for that request, and included as `correlationId` in every error body.

Clients may send their own `X-Correlation-Id` to trace a request across systems. It's accepted if it's 1–64 characters of `[A-Za-z0-9._-]`; anything else is replaced with a generated ID.

To trace a failed request: take the `correlationId` from the error response and search the logs for it.

### Logs

- **dev:** human-readable, with the correlation ID in brackets on each line.
- **prod:** structured JSON (Elastic Common Schema). The correlation ID is a top-level field.
- One line per request: method, path, status, duration. Bodies, headers and query strings are never logged.
- Account-related logs carry the account **id**, never an email, password or token. The one exception is the dev-only email sender, which logs each email, links included.

### Actuator

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Overall status; component details only for `ADMIN` |
| `/actuator/health/liveness` | Is the process alive — failure means *restart me* |
| `/actuator/health/readiness` | Can it take traffic — failure means *stop routing to me* |
| `/actuator/info` | Build version and time, git branch and commit |
| `/actuator/metrics` | Micrometer metrics, e.g. `/actuator/metrics/http.server.requests` (`ADMIN` only) |

Health (including the probes) and info are public, because orchestrators call them anonymously. No other Actuator endpoints are exposed. Shutdown is graceful, with a 30-second limit for in-flight requests.

---

## Testing

```bash
./mvnw test
```

**Docker must be running** — repository and integration tests start a real PostgreSQL 17 container through Testcontainers. There is deliberately no H2: it behaves differently from Postgres on locking, constraint errors and index behaviour.

| Kind | Example | What it covers |
|---|---|---|
| Unit | `OrganizationServiceTest` | Business rules, with the repository mocked |
| Web slice (`@WebMvcTest`) | `OrganizationControllerTest` | HTTP contract — status codes, JSON, validation, error bodies |
| JPA slice (`@DataJpaTest`) | `OrganizationRepositoryTest` | Mappings, queries, constraints, auditing, against real Postgres |
| Integration (`@SpringBootTest`) | `OrganizationApiIntegrationTest` | The full stack over real HTTP |
| Security | `TaskflowSecurityConfigTest`, `TaskflowSecurityIntegrationTest` | Every access rule as a table-driven test; real credentials, actuator rules, no sessions |

Flyway runs inside the test container, so every migration is exercised on every build. See [`docs/phase-0/TESTING_GUIDE.md`](docs/phase-0/TESTING_GUIDE.md) and [`docs/phase-1/SECURITY_TESTING_GUIDE.md`](docs/phase-1/SECURITY_TESTING_GUIDE.md).

---

## Project structure

Package-by-feature. A feature package never imports another feature package; anything shared lives in `common`.

```
src/main/java/com/abhinav/taskflow/
├── TaskflowApplication.java
├── common/
│   ├── config/        typed properties, auditing, the Clock bean
│   ├── error/         ProblemDetail handler, error codes, exception types
│   ├── logging/       correlation ID + request logging filter
│   ├── mail/          EmailSender (dev: logs the email; prod: none yet)
│   ├── persistence/   IdentifiedEntity (id), BaseEntity (+ audit columns)
│   ├── security/      the filter chain, password encoder, principal, authentication provider
│   ├── util/          email / username normalisation
│   ├── validator/     @ValidPassword, @MaxUtf8Bytes
│   └── web/           pagination, ClientInfo
├── organization/      controller, service, repository, entity, DTOs, error codes
└── user/              accounts, registration, login, lockout, password reset/change
    ├── token/         verification and reset tokens (hashed, expiring, single-use)
    └── event/         the append-only security event log
```

---

## Key design decisions

- **Flyway + `ddl-auto=validate`** — schema changes are versioned and reviewable, and an entity that drifts from the schema stops the app at startup.
- **`open-in-view=false`** — lazy loading outside a transaction fails loudly instead of silently firing queries during JSON serialisation.
- **`bigint` IDs from a pooled sequence** — compact foreign keys and JDBC batch inserts. `IDENTITY` would disable batching.
- **`timestamptz` + `Instant`** — timestamps are absolute instants; time zones are applied at the edge.
- **One error contract** — domain and framework errors share a single `ProblemDetail` shape, stamped in one place.
- **Service check + database constraint** for uniqueness — the service handles the common case cleanly; the unique constraint catches the concurrent race; both return the same 409.
- **Testcontainers, never H2** — tests run against the database that's actually shipped.

- **Deny by default** — the last access rule is `denyAll()`, so an endpoint nobody declared is closed, not open to every signed-in user.
- **A separate principal, not the entity** — the security context lives outside transactions; it carries the id and username, never a detached entity.
- **Tokens hashed at rest, single-use by one conditional `UPDATE`** — a leaked database holds no working links, and two clicks can't both succeed. Links carry the token in the URL fragment, which never reaches a server log.
- **Emails after commit** — no email for a change that rolled back, and no transaction held open for mail.
- **Lockout driven by authentication events** — every password check counts, Basic included, in its own transaction so a failed login can't roll its own count back.
- **Append-only security log, enforced by the database** — a trigger rejects `UPDATE`; IPs come from the socket, never from a client-supplied header.

The reasoning behind each is recorded in [`docs/phase-0/PHASE_0_LEARNING_LOG.md`](docs/phase-0/PHASE_0_LEARNING_LOG.md) and [`docs/phase-1/PHASE_1_LEARNING_LOG.md`](docs/phase-1/PHASE_1_LEARNING_LOG.md).

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 0 | Foundations — config, migrations, error contract, logging, testing | ✅ Done |
| 1 | Users & authentication — registration, email verification, login, lockout, password reset and change, security event log | ✅ Done |
| 2 | JWT with refresh-token rotation and reuse detection, logout, revocation (replaces Basic); user profile | Next |
| 3 | Organizations as tenants — membership, invitations, tenant isolation | Planned |
| 4 | Fine-grained, per-project authorization | Planned |
| 5 | Issues — type hierarchy, concurrency-safe issue keys, optimistic locking | Planned |
| 6 | Configurable workflow engine | Planned |
| 7–8 | Field-level audit history, soft delete, search, performance | Planned |
| 9 | Events and async notifications | Planned |

---

## Development notes

- **`/actuator/info` is empty when running from IntelliJ's ▶ button.** IntelliJ's own build doesn't run Maven plugins, so build and git info are never generated. Run with `./mvnw spring-boot:run`, or in the Maven tool window right-click **Lifecycle → generate-resources → Execute Before Build**.
- **Run `./mvnw clean test`** if test reports look stale — deleted test classes can leave old reports behind.
- **`./mvnw spring-boot:run` doesn't reload code.** Restart after a change, or you're testing the old build.
- **IntelliJ's "Delegate build/run actions to Maven" runs the tests before starting the app**, so a failing test means no app. Use `./mvnw spring-boot:run`, or *Runner → Skip Tests* for IDE runs.

---

## Documentation

| Document | Contents |
|---|---|
| [`docs/PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md) | Goals, full feature list, 12-week plan, engineering standards |
| [`docs/phase-0/PHASE_0_REQUIREMENTS.md`](docs/phase-0/PHASE_0_REQUIREMENTS.md) | Phase 0 requirements and checklist |
| [`docs/phase-0/PHASE_0_LEARNING_LOG.md`](docs/phase-0/PHASE_0_LEARNING_LOG.md) | Decisions, problems encountered, practices, interview notes |
| [`docs/phase-0/TESTING_GUIDE.md`](docs/phase-0/TESTING_GUIDE.md) | How the test suite is built and run |
| [`docs/phase-1/PHASE_1_REQUIREMENTS.md`](docs/phase-1/PHASE_1_REQUIREMENTS.md) | Phase 1 briefs, decisions and requirements, sub-section by sub-section |
| [`docs/phase-1/PHASE_1_LEARNING_LOG.md`](docs/phase-1/PHASE_1_LEARNING_LOG.md) | Phase 1 lessons, practices and interview notes, by theme |
| [`docs/phase-1/SECURITY_TESTING_GUIDE.md`](docs/phase-1/SECURITY_TESTING_GUIDE.md) | How the access rules are tested |
