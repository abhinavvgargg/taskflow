# Spring 4. Observability and operations

> Logs you can trust in an incident, Actuator that helps operators without helping attackers, and the local infrastructure. Sources: [../SOURCES.md](../SOURCES.md).

## Logging

- **Parameterised SLF4J** (`log.debug("Found {} orgs", count)`), never concatenation: concatenation builds the string even when the level is off, and breaks structured-field extraction.
- **Ids, never personal data or credentials.** Logs are copied, shipped and kept. Account-related lines carry the account id only; records holding passwords, tokens or emails override `toString()` (*seen*: one `log.info("{}", request)` printed a plaintext password, Phase 1). Never log a built cookie or response headers: the `Set-Cookie` text holds the raw refresh token (Phase 2). The dev-only email sender is the one deliberate exception, and dev logs SQL parameters at TRACE.
- **One completion line per request** at INFO (method, path, status, duration; no bodies, headers, token-bearing query strings or PII). *What happened to us (Phase 0):* a successful request logged only dev-only SQL lines, and 4xx were logged at DEBUG, so in prod a user could quote a correlation id from a 404 and the logs had no record of it.
- **The correlation id** is generated or validated in a filter, put in the MDC (a `ThreadLocal` map the logging framework reads via `%X{…}`), echoed in the `X-Correlation-Id` header and in every error body, and cleared in `finally` ([03](03-filters-interceptors-and-request-scope.md)). Boot's default pattern has an empty correlation slot, fillable with `logging.pattern.correlation`.
- **JSON logs in prod:** Boot 3.4+ has native structured logging (`logging.structured.format.console=ecs`), no logstash encoder needed; MDC entries become top-level queryable fields.
- **Production tracing** would use Micrometer Tracing / OpenTelemetry with W3C `traceparent`; the hand-rolled correlation id is for learning the mechanism.
- **Logger names are case-sensitive** ([jpa 04](../jpa/04-queries-projections-and-paging.md)).
- **A failure that's only logged can hide a broken feature.** *What happened to us (Phase 1):* the lockout listener caught database errors and logged them, every wrong password still got a 401, and lockout was silently off. Security counters fail loudly instead ([security 05](../security/05-account-protection-and-privacy.md)).

## Actuator

- **Expose only `health`, `info`, `metrics`;** never `*` (`/env` and `/configprops` dump configuration, `/heapdump` dumps memory including secrets). Boot 3.5's default exposes only `health`.
- **Health is public, its details admin-only.** Orchestrators call the probes anonymously; a probe that needs a login looks dead and gets restarted in a loop. `show-details: when_authorized` **plus** `roles: ADMIN`: without the role, "authorized" means any signed-in user, and anyone can sign up. *What happened to us (Phase 1):* the first version locked the whole endpoint to admins; `anonymous()` on it gave a signed-in admin 403.
- **Probes:** `management.endpoint.health.probes.enabled: true` (auto-on only when Kubernetes is detected). **Liveness failing means "restart me", readiness failing means "stop sending traffic".** Liveness must not depend on the database: a 10-second database blip would restart every instance at once, turning a partial outage into a total one.
- **`/actuator/info` from real build info:** Boot's parent manages `git-commit-id-maven-plugin`; use `management.info.git.mode: simple`. *What happened to us (Phase 0):* `full` exposed a personal email, the build host's internal IP and the remote URL on an unauthenticated endpoint. (IntelliJ's builder skips the plugin goal: run `generate-resources` before build.)
- **`metrics` is admin-only;** `prometheus` waits until there's a scraper and its registry (listing it without the registry silently exposes nothing). A separate `management.server.port` is the production pattern for keeping ops endpoints off the public port (optional).
- **Graceful shutdown** is Boot 3.4+'s default (`server.shutdown: graceful`); the timeout is set explicitly (`spring.lifecycle.timeout-per-shutdown-phase: 30s`) as a visible decision.

## Local infrastructure (Docker Compose)

- **PostgreSQL 17** (`postgres:17-alpine`) in `compose.yaml`; tests use the same image through Testcontainers.
- **`.env` beside `compose.yaml` is read by Compose** for `${VAR}` substitution; **`env_file:` passes variables into the container**: different mechanisms.
- **`CMD-SHELL` health checks run through a shell** (so `${VAR}` expands); `CMD` doesn't.
- **PostgreSQL initialises only on an empty data directory:** changing the password later does nothing until `down -v`.
- **Scratch copies for experiments:** deliberate failures and mutation checks run in a copy of the project (with `-Dmaven.gitcommitid.skip=true`, since the copy has no `.git`) against a scratch database, then are deleted; the working tree and dev database are never touched.

## Interview questions

1. **A user reports a failed request at 14:32. How do you find it?** The correlation id from the response header or error body; it's on every log line for that request and a queryable field in the JSON logs.
2. **What's the difference between liveness and readiness, and why keep the database out of liveness?** Liveness restarts the instance, readiness only stops traffic; a database blip in liveness restarts the whole fleet at once.
3. **Which Actuator endpoints do you expose?** Health (public, details admin-only), info (build and git, simple mode), metrics (admin); never `*`.
4. **How would you debug a slow query in production?** Database-side metrics and slow-query logs, not DEBUG logging in the app.
