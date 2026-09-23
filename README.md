# TaskFlow — Multi-Tenant Project & Issue Tracking Platform

A REST API for project and issue tracking, built as a deliberate exercise in the problems that make backend systems hard: per-project authorization, multi-tenancy, concurrent edits, a configurable workflow engine, audit history, and query performance.

> **Status — Phase 0 (foundations) complete.** The production-grade skeleton is in place, with **Organizations** as the first vertical slice. Authentication, multi-tenancy, projects, issues and the workflow engine are on the [roadmap](#roadmap) and are **not built yet**.

---

## Tech stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.16 (Spring Framework 6.2) |
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

**Reset the local database** (deletes all data):

```bash
docker compose down -v && docker compose up -d
```

> Postgres only reads `POSTGRES_*` variables when its data directory is empty. If you change the password in `.env` after the first run, reset the volume for it to take effect.

---

## API

Base path: **`/api/v1`**. All requests and responses are JSON; errors are `application/problem+json`.

### Organizations

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/v1/organizations` | **201** + `Location` header, body = created organization | 400, 409 |
| `GET` | `/api/v1/organizations/{id}` | **200** | 404 |
| `GET` | `/api/v1/organizations` | **200**, paginated | 400 |

**Create**

```bash
curl -i -X POST localhost:8080/api/v1/organizations \
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
curl -s 'localhost:8080/api/v1/organizations?page=0&size=20&sort=name,asc'
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

Every error — from application code or from the framework — uses [RFC 7807 Problem Details](https://www.rfc-editor.org/rfc/rfc7807) with a stable, machine-readable `code`.

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
| `BAD_REQUEST` | 400 | Malformed JSON or unreadable request |
| `ORGANIZATION_NOT_FOUND` | 404 | No organization with that id |
| `NOT_FOUND` | 404 | Unknown path |
| `METHOD_NOT_ALLOWED` | 405 | Unsupported HTTP method |
| `DUPLICATE_SLUG` | 409 | Slug already taken |
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

None of these have defaults. **A missing variable stops the application at startup** rather than letting it fall back to a guessed value.

### Application settings

| Property | Default | Validation |
|---|---|---|
| `taskflow.api.default-page-size` | 20 | ≥ 1, and ≤ `max-page-size` |
| `taskflow.api.max-page-size` | 100 | 1–100 |

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

### Actuator

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Overall status (component details hidden) |
| `/actuator/health/liveness` | Is the process alive — failure means *restart me* |
| `/actuator/health/readiness` | Can it take traffic — failure means *stop routing to me* |
| `/actuator/info` | Build version and time, git branch and commit |
| `/actuator/metrics` | Micrometer metrics, e.g. `/actuator/metrics/http.server.requests` |

No other Actuator endpoints are exposed. Shutdown is graceful, with a 30-second limit for in-flight requests.

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

Flyway runs inside the test container, so every migration is exercised on every build. See [`docs/phase-0/TESTING_GUIDE.md`](docs/phase-0/TESTING_GUIDE.md).

---

## Project structure

Package-by-feature. A feature package never imports another feature package; anything shared lives in `common`.

```
src/main/java/com/abhinav/taskflow/
├── TaskflowApplication.java
├── common/
│   ├── config/        typed properties, auditing configuration
│   ├── error/         ProblemDetail handler, error codes, exception types
│   ├── logging/       correlation ID + request logging filter
│   ├── persistence/   BaseEntity (id + audit columns)
│   └── web/           pagination: PageResponse, PageableFactory
└── organization/      controller, service, repository, entity, DTOs, error codes
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

The reasoning behind each is recorded in [`docs/phase-0/PHASE_0_LEARNING_LOG.md`](docs/phase-0/PHASE_0_LEARNING_LOG.md).

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 0 | Foundations — config, migrations, error contract, logging, testing | ✅ Done |
| 1 | Users & authentication — registration, email verification, password reset, lockout | Planned |
| 2 | JWT with refresh-token rotation and reuse detection, logout, revocation | Planned |
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

---

## Documentation

| Document | Contents |
|---|---|
| [`docs/PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md) | Goals, full feature list, 12-week plan, engineering standards |
| [`docs/phase-0/PHASE_0_REQUIREMENTS.md`](docs/phase-0/PHASE_0_REQUIREMENTS.md) | Phase 0 requirements and checklist |
| [`docs/phase-0/PHASE_0_LEARNING_LOG.md`](docs/phase-0/PHASE_0_LEARNING_LOG.md) | Decisions, problems encountered, practices, interview notes |
| [`docs/phase-0/TESTING_GUIDE.md`](docs/phase-0/TESTING_GUIDE.md) | How the test suite is built and run |
