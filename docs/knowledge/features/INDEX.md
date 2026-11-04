# Features — where each one is explained

> "How do I implement X with Spring Boot?": for every feature TaskFlow has built, where its **rebuild recipe** lives (the phase implementation references: what, how, rules, pitfalls, files), the **brief** with its traps and deliberate failures, and the **knowledge pages** that explain the mechanisms it relies on. The recipes are not copied here: each phase's `PHASE_N_IMPLEMENTATION_REFERENCE.md` stays the one place they're maintained (Part D of each is a step-by-step rebuild order).

**Recipe files:** [P1 reference](../../phase-1/PHASE_1_IMPLEMENTATION_REFERENCE.md) · [P2 reference](../../phase-2/PHASE_2_IMPLEMENTATION_REFERENCE.md) · [P3 reference](../../phase-3/PHASE_3_IMPLEMENTATION_REFERENCE.md) · Phase 0 has no reference; its briefs are in [P0 requirements](../../phase-0/PHASE_0_REQUIREMENTS.md).

## Foundations (Phase 0)

| Feature | Brief | Mechanisms |
|---|---|---|
| Project skeleton, package by feature | P0 req §0.1 | [spring 01](../spring/01-boot-configuration-and-beans.md) |
| PostgreSQL via Docker Compose | P0 req §0.2 | [spring 04](../spring/04-observability-and-operations.md) |
| Profiles and typed configuration | P0 req §0.3 | [spring 01](../spring/01-boot-configuration-and-beans.md) |
| Flyway and schema conventions | P0 req §0.4 | [jpa 06](../jpa/06-schema-constraints-and-invariants.md) |
| `BaseEntity` and JPA auditing | P0 req §0.5 | [jpa 01](../jpa/01-entities-and-mapping.md), [jpa 07](../jpa/07-auditing-and-time.md) |
| Web contracts: DTOs, pagination, sorting | P0 req §0.6 | [spring 02](../spring/02-web-layer-validation-and-errors.md), [jpa 04](../jpa/04-queries-projections-and-paging.md) |
| Global error handling (`ProblemDetail`) | P0 req §0.7 | [spring 02](../spring/02-web-layer-validation-and-errors.md) |
| Correlation id and structured logging | P0 req §0.8 | [spring 03](../spring/03-filters-interceptors-and-request-scope.md), [spring 04](../spring/04-observability-and-operations.md) |
| Actuator (OpenAPI moved to Phase 11) | P0 req §0.9 | [spring 04](../spring/04-observability-and-operations.md) |
| The testing baseline | P0 req §0.10, `phase-0/TESTING_GUIDE.md` | [testing 01](../testing/01-test-strategy-and-slices.md), [jpa 09](../jpa/09-testing-jpa.md) |

## Users and authentication (Phase 1)

| Feature | Recipe | Mechanisms |
|---|---|---|
| The security gate (filter chain, access rules) | P1 ref C1 | [security 01](../security/01-filter-chain-and-access-rules.md) |
| Accounts, password storage, the principal | P1 ref C2 | [security 02](../security/02-password-authentication.md), [jpa 01](../jpa/01-entities-and-mapping.md) |
| The password check and what it reveals | P1 ref C3 | [security 02](../security/02-password-authentication.md), [security 05](../security/05-account-protection-and-privacy.md) |
| Registration | P1 ref C4 | [jpa 02](../jpa/02-persistence-context-and-flushing.md) (`saveAndFlush`), [spring 02](../spring/02-web-layer-validation-and-errors.md) |
| Link tokens (verification, reset) | P1 ref C5 | [security 03](../security/03-email-link-tokens.md), [jpa 05](../jpa/05-bulk-updates-and-concurrency.md) |
| Email verification, resend, sending after commit | P1 ref C6 | [jpa 03](../jpa/03-transactions.md) |
| Login | P1 ref C7 | [security 02](../security/02-password-authentication.md), [jpa 03](../jpa/03-transactions.md) |
| Lockout | P1 ref C8 | [security 05](../security/05-account-protection-and-privacy.md), [jpa 05](../jpa/05-bulk-updates-and-concurrency.md) |
| Security event log and login history | P1 ref C9 | [security 05](../security/05-account-protection-and-privacy.md), [jpa 06](../jpa/06-schema-constraints-and-invariants.md) |
| Password reset | P1 ref C10 | [security 05](../security/05-account-protection-and-privacy.md), [jpa 02](../jpa/02-persistence-context-and-flushing.md) (`@DynamicUpdate`) |
| Password change (now via `Reauthentication`) | P1 ref C11 | [security 02](../security/02-password-authentication.md) |
| Input rules: passwords, normalisation, masking | P1 ref C12 | [spring 02](../spring/02-web-layer-validation-and-errors.md) |

## JWT and sessions (Phase 2)

| Feature | Recipe | Mechanisms |
|---|---|---|
| Signing keys and JWT configuration | P2 ref C1 | [security 04](../security/04-jwt-refresh-and-sessions.md), [spring 01](../spring/01-boot-configuration-and-beans.md) |
| Issuing access tokens | P2 ref C2 | [security 04](../security/04-jwt-refresh-and-sessions.md) |
| Sessions and refresh tokens: schema, entities, repositories | P2 ref C3 | [jpa 05](../jpa/05-bulk-updates-and-concurrency.md) (insert-only) |
| Login: starting a session, the cookie | P2 ref C4 | [security 04](../security/04-jwt-refresh-and-sessions.md) |
| Verifying bearer tokens on every request | P2 ref C5 | [security 04](../security/04-jwt-refresh-and-sessions.md), [security 01](../security/01-filter-chain-and-access-rules.md) |
| The request principal and its readers | P2 ref C6 | [security 02](../security/02-password-authentication.md), [jpa 07](../jpa/07-auditing-and-time.md) |
| One error format for the filter chain | P2 ref C7 | [security 01](../security/01-filter-chain-and-access-rules.md) |
| Refresh: rotation and reuse detection | P2 ref C8 | [security 04](../security/04-jwt-refresh-and-sessions.md), [jpa 03](../jpa/03-transactions.md) |
| The `Origin` check on cookie endpoints | P2 ref C9 | [security 06](../security/06-cors-and-csrf.md) |
| Security events added by Phase 2 | P2 ref C10 | [security 05](../security/05-account-protection-and-privacy.md) |
| Logout, session list, revoke one, log out everywhere | P2 ref C11 | [security 04](../security/04-jwt-refresh-and-sessions.md) |
| Revocation on password reset and change | P2 ref C12 | [jpa 03](../jpa/03-transactions.md) (`MANDATORY`) |
| CORS | P2 ref C13 | [security 06](../security/06-cors-and-csrf.md) |

## Organizations and multi-tenancy (Phase 3)

| Feature | Recipe | Mechanisms |
|---|---|---|
| Memberships and the owner invariant | P3 ref C1 | [jpa 06](../jpa/06-schema-constraints-and-invariants.md) |
| The tenant gate | P3 ref C2 | [spring 03](../spring/03-filters-interceptors-and-request-scope.md), [security 07](../security/07-tenancy-and-org-authorization.md) |
| The request-scoped tenant context | P3 ref C3 | [spring 03](../spring/03-filters-interceptors-and-request-scope.md) |
| My organizations, one organization, its members | P3 ref C4 | [jpa 04](../jpa/04-queries-projections-and-paging.md) |
| Managing members: change role, remove, leave | P3 ref C5 | [jpa 05](../jpa/05-bulk-updates-and-concurrency.md), [security 07](../security/07-tenancy-and-org-authorization.md) |
| Ownership transfer and shared re-authentication | P3 ref C6 | [security 02](../security/02-password-authentication.md), [jpa 03](../jpa/03-transactions.md) |
| Invitations (§3.3, in progress) | P3 req §3.3 | [security 07](../security/07-tenancy-and-org-authorization.md), [jpa 05](../jpa/05-bulk-updates-and-concurrency.md) |
| `@TenantId` on projects (§3.4, next) | P3 req §3.4, D4 | [jpa 08](../jpa/08-multi-tenancy-with-hibernate.md) |
