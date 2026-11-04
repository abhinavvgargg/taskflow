# Spring 2. The web layer: contracts, validation and errors

> How TaskFlow's HTTP API is shaped: DTOs, validation that answers the same way for the same mistake, one error format for everything, and the HTTP semantics each endpoint follows. Sources: [../SOURCES.md](../SOURCES.md).

## Layering and DTOs

- **Controller (HTTP only) → service (business rules + transactions) → repository.** No business logic in controllers.
- **Separate request/response records; entities never cross the web layer.** Three reasons: **coupling** (your JSON becomes your schema), **over-exposure** (every entity field is published, including a password hash), **input trust** (accepting an entity means accepting a client-supplied `id` and audit fields).
- **Mapping:** a static factory on the response record (`OrganizationResponseDto.from(...)`) or a JPQL constructor projection; no MapStruct at this size (Phase 0 decision 11). Map **inside** the transaction ([jpa 02](../jpa/02-persistence-context-and-flushing.md)).
- **Controllers that sit under the tenant gate don't bind `{orgId}`;** services read the organization from `TenantContext` ([03](03-filters-interceptors-and-request-scope.md)).
- **The caller comes from the principal, never from the URL or body:** `@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser caller` (fails loudly on a wrong principal type).

## Validation: one mistake, one answer

- **Bean Validation at the boundary** (`@Valid @RequestBody`), business rules in services, constraints in the database ([jpa 06](../jpa/06-schema-constraints-and-invariants.md)).
- **`@Max` is numeric.** *What happened to us (Phase 0):* `@Max(100)` on a `String` name made Hibernate Validator parse the string as a number, so **every real organization name was rejected** with "must be less than or equal to 100". Fix: `@Size`. A `@WebMvcTest` asserting the response would have caught it in seconds.
- **`@Size` counts Java characters, not bytes.** bcrypt's limit is 72 **bytes**; an emoji is 2 characters and 4 bytes. `@MaxUtf8Bytes(72)` (a custom constraint) turns an over-long password into a clean 400 instead of an encoder 500 ([security 02](../security/02-password-authentication.md)).
- **Custom constraints:** an annotation plus a validator class; treat `null` as valid (presence is `@NotBlank`'s job); messages in **`ValidationMessages.properties`** (plural: *what happened to us*, the singular name showed raw `{…}` keys). Composed constraints (`@ValidPassword` = not blank + ≥ 12 characters + ≤ 72 bytes) need each part to allow `ElementType.ANNOTATION_TYPE`.
- **Validate enum-like input as a `String` with `@Pattern`, convert in code** (Phase 3 §3.2): an `OrganizationRole`-typed field makes `GOD` a JSON parse error (400 `BAD_REQUEST`, no field: `GlobalExceptionHandler` doesn't override `handleHttpMessageNotReadable`, *verified*) while `OWNER` sails through to the service. With `@NotNull @Pattern(regexp = "ADMIN|MEMBER") String role`, `OWNER`, `GOD`, `admin` and `""` all get one 400 `VALIDATION_FAILED` with `field: role`. §3.3's `InviteRequest` follows the same rule.
- **Login and "current password" fields check only "not blank, ≤ 72 bytes",** never the minimum length, so tightening the rule later can't lock out older passwords.
- **Normalise once, at the top, then use those values everywhere** (lowercase emails/usernames with `Locale.ROOT`; the Turkish `I → ı` trap). *What happened to us (Phase 1):* registration checked duplicates with the email as typed, then saved the lowercased one.
- **Multiple errors per field come back ungrouped, and `@Pattern`'s default message echoes the regex** (Phase 0 nits, still open).

## One error format: `ProblemDetail`

- **RFC 7807 `ProblemDetail`** for every error, extended with a stable `code` (an enum, the public contract), `correlationId`, `timestamp`, `fieldErrors` for validation, and structured properties (`slug`, `constraint`, `userId`, `field`).
- **Extend `ResponseEntityExceptionHandler`** (it already handles the MVC exceptions, one protected method each) and override **`handleExceptionInternal`**: call `super` first and **enrich** the `ProblemDetail` it produced, or you discard the framework's title, detail and message resolution.
- **Two kinds of error:** *domain* errors carry their own `ErrorCode` (you decided what they mean); *protocol* errors (405, 415, malformed JSON) are classified by their status, so the code is derived from the status with a couple of explicit overrides. You enumerate a handful of statuses, not twenty exception types.
- **`ErrorCode` interface in `common`, one enum per feature;** exception *types* stay generic (`ResourceNotFoundException`, `ResourceConflictException`, `RequestForbiddenException`, `ResourceInvalidException`), the *codes* live with their feature (Phase 0 decision 13). One code, one home (`ORGANIZATION_NOT_FOUND` moved to `TenantErrorCode` in Phase 3).
- **`DataIntegrityViolationException` → 409 `RESOURCE_CONFLICT` with the constraint name**, including violations found at commit ([jpa 03](../jpa/03-transactions.md)).
- **The 500 handler is the one that matters:** full exception logged at ERROR with the correlation id; the client gets a generic message and the correlation id, nothing else. `server.error.include-stacktrace: never` and `include-message: never` in every profile (they default friendlier in dev and get copy-pasted).
- **Leaks still open (Phase 0):** unknown paths answer `"No static resource api/v1/nope."`; type-conversion messages name `java.lang.Integer`. And the "never echo `rejectedValue`" test is owed (Phase 11).
- **Errors raised before Spring MVC** (in the security filter chain) never reach `@RestControllerAdvice`; the entry point and access-denied handler write the same `ProblemDetail` shape ([security 01](../security/01-filter-chain-and-access-rules.md)).
- **A rejection that must set a header is built in the controller, not thrown:** an exception handler wouldn't add the clearing `Set-Cookie` (Phase 2 refresh). Spring MVC writes a `ResponseEntity<ProblemDetail>` as `application/problem+json`.
- **Throw clean domain exceptions from services; never `ResponseEntity.status(409)` in a service** (Phase 0).

## HTTP semantics TaskFlow follows

- **201 + `Location`** for creates (`/api/v1/organizations/{id}`), except registration: no `Location`, because there's no URL where the caller could read another user.
- **204** for deletes and actions with nothing to return (logout, transfer).
- **400 vs 401:** a wrong *current password* is 400 `CURRENT_PASSWORD_INCORRECT` with `field: currentPassword`, not 401, which a client would treat as "signed out".
- **404 vs 403:** across an ownership or tenant boundary every miss is the same 404 (ids are guessable); 403 only inside a boundary you belong to ([security 05](../security/05-account-protection-and-privacy.md), [security 07](../security/07-tenancy-and-org-authorization.md)).
- **409** for conflicts with stored state (duplicates, `MEMBERSHIP_CHANGED`, `OWNER_CANNOT_LEAVE`).
- **Path matching:** Spring Framework 6 doesn't match a trailing slash (`DELETE /users/me/sessions/` is a 404, not "delete all", *seen*); a literal segment beats a variable (`/members/me` over `/members/{userId}`; with a `Long` variable, `me` would otherwise be a 400).
- **JSON names are a contract:** renaming a field breaks clients. *Caught before any client existed:* a misspelt `occuredAt`; `password` in one request vs `newPassword` in another. **Durations in seconds** (`expiresIn`).
- **Pagination on every list,** clamped size, whitelisted sort, stable tiebreaker ([jpa 04](../jpa/04-queries-projections-and-paging.md)).

**Caught in Phase 1 review** (each would have shipped): a create returning 200 instead of 201; `@Size(min = 3)` on display names, which rejects real names like "Li" or "王"; a 409 that didn't say which field clashed (now a property such as `slug` or `field`); a verification link hard-coded to `localhost:3000` (now `FrontendProperties`); `/auth/login` with no exception handling (every wrong password a 500); a response built from the principal with `null` fields and a hard-coded `emailVerified: false`; `.get()` on lookups; unknown errors wrapped in a plain `RuntimeException`; `.with("field", …)` called twice on one error.

## OpenAPI (moved to Phase 11)

Generated OpenAPI describes the code's shape, not the contract; springdoc out of the box was wrong four ways (*verified* on Boot 3.5.16): `PageQuery`/`Sort` rendered as objects (fix: `@ParameterObject` on both), `POST` shown as 200 (`@ApiResponse(responseCode = "201")`), everything `*/*` (`springdoc.default-produces-media-type: application/json`), and no error responses (document the `ProblemDetail` ones). Use **springdoc 2.9.1** (3.x targets Boot 4); Swagger UI only in `dev`. Kept off the classpath until then: it serves the UI under `prod` too.

## Interview questions

1. **Why don't entities cross the web layer?** Coupling, over-exposure, and trusting client-supplied ids and audit fields.
2. **Walk me through your error contract.** RFC 7807 `ProblemDetail`, a stable `code` enum, structured properties, a correlation id; one funnel so domain and framework errors look the same, including those from the filter chain.
3. **Where could a 500 leak information?** Exception messages carry schema, paths, versions and SQL: generic body to the client, details only in the log.
4. **Why validate `role` as a `String`?** So every invalid value gets the same 400 on the same field; an enum field turns some into parse errors and lets others through.
5. **Why extend `ResponseEntityExceptionHandler`?** The MVC exceptions are already handled, and `handleExceptionInternal` is the single funnel to stamp shared fields.
