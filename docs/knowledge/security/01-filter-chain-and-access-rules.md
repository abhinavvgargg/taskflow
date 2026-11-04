# Security 1. The filter chain and access rules

> How Spring Security decides who gets in, and the rules TaskFlow's chain follows. Most of §1.1's problems came from rules that looked right and weren't, with no sign until a request arrived. Sources: [../SOURCES.md](../SOURCES.md).

## The chain, in order

A request passes `DelegatingFilterProxy` (the bridge from Tomcat, which knows nothing about Spring beans) → `FilterChainProxy` (picks the matching `SecurityFilterChain` and clears the security context afterwards) → the chain's filters: CORS, security context, headers, the bearer-token filter, anonymous, exception translation, authorization. Only then does `DispatcherServlet` run. `CorrelationIdFilter` runs before all of them, so rejected requests have a correlation id too. Phase 10 adds a second, ordered chain for API keys.

- **401** = "I don't know who you are"; **403** = "I know who you are, and no".
- **`denyAll()` gives an anonymous caller 401, not 403:** it can't forbid someone it hasn't identified.
- **Authentication runs before authorization:** an invalid bearer token is rejected even on a `permitAll` path (*verified*); that's why TaskFlow's bearer-token resolver ignores tokens on `/api/v1/auth/**`, or a stale header would lock a client out of login and refresh (*seen*, §2.2 deliberate failure 2).

## Rules that are checked only when a request arrives

URL patterns are validated per request, not at startup. *What happened to us (Phase 1):* the public paths written as `"/api/v1/auth/{register, login, …}"` (Spring reads `{…}` as a path variable, and commas aren't allowed in its name) made **every POST a 500**; `EndpointRequest.to("health/**")` (it takes endpoint **names**) made **every GET a 500**. Both started without a warning. **Rule:** every rule is a row in a table-driven test ([testing 02](../testing/02-security-race-and-time-tests.md)).

## TaskFlow's rules

```
POST to the exact public auth paths (register, login, verify, resend, reset request/confirm, refresh, logout) → permitAll
actuator health, info → permitAll (details restricted separately) · metrics → ADMIN
/error → permitAll
/api/v1/** → authenticated
anything else → denyAll
```

- **Close everything not explicitly opened:** `anyRequest().denyAll()` last. `authenticated()` would mean "any signed-in user may call any endpoint nobody thought about", and anyone can sign up.
- **Specific before general; first match wins;** public paths listed exactly, and only for `POST`.
- **`permitAll()` vs `anonymous()`:** `anonymous()` lets in only callers who aren't signed in. *What happened to us:* the health endpoint marked `anonymous()` gave a signed-in admin 403.
- **`/error` is `permitAll`:** Tomcat's `sendError` makes an internal second request to `/error` that passes through security again; if it isn't public, every error becomes a bare 401 with an empty body.
- **Roles need the `ROLE_` prefix:** `hasRole("ADMIN")` looks for the authority `ROLE_ADMIN`; `hasAuthority` takes the exact string.
- **Fine-grained, per-resource authorization** (method security, a `PermissionEvaluator`) is Phase 4; per-organization access is the tenant gate ([07](07-tenancy-and-org-authorization.md)).

## Defaults to switch off or keep

- **Declaring your own beans removes Boot's:** any `SecurityFilterChain` removes the default chain; a user service, provider or manager removes the generated user ([spring 01](../spring/01-boot-configuration-and-beans.md)).
- **Stateless:** `SessionCreationPolicy.STATELESS` only stops Spring Security creating a session; other code still could, so a test checks that no response carries `Set-Cookie`.
- **CSRF off, with the reason written next to it:** bearer tokens are never attached by the browser. The two endpoints that read the refresh **cookie** are protected differently ([06](06-cors-and-csrf.md)).
- **`LogoutFilter` is on by default and runs before the access rules,** so `denyAll()` didn't apply to `/logout`: it's disabled; TaskFlow's logout is its own endpoint.
- **Default security headers kept.**
- **HTTP Basic** carried Phase 1 and was deleted in Phase 2; Basic credentials now arrive as "no bearer token" (`AUTHENTICATION_REQUIRED`).

## One error format for the chain

Errors in the filter chain happen before Spring MVC, so `@RestControllerAdvice` never sees them (the same exception thrown from a controller *is* handled). TaskFlow sets a `ProblemDetail`-writing `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403):
- **The entry point must be set in both places:** the bearer filter holds its own (set by `OAuth2ResourceServerConfigurer`); `exceptionHandling` covers protected paths reached with no token. Set only one and half the 401s keep Spring's body (*seen*, §2.2 deliberate failure 3).
- **It writes the body itself** (avoiding the `/error` dispatch), with Boot's `ObjectMapper` so the `ProblemDetail` matches MVC's; it writes only the error code, not the library's `error_description`.
- **401s carry `WWW-Authenticate: Bearer`** (with `error="invalid_token"` when a token was presented), so a client knows to refresh or log in.
- **The one exception:** a disallowed cross-origin request gets Spring's plain-text CORS 403 (Phase 2 D15, [06](06-cors-and-csrf.md)).

## Interview questions

1. **What happens between a request arriving and your controller running?** `DelegatingFilterProxy` → `FilterChainProxy` → the chain's filters (context, headers, token, anonymous, exception translation, authorization) → `DispatcherServlet`.
2. **Why three pieces: `DelegatingFilterProxy`, `FilterChainProxy`, `SecurityFilterChain`?** Tomcat can't see Spring beans (the proxy bridges); `FilterChainProxy` is the single entry that picks a chain and cleans up; chains are what I declare.
3. **Why isn't `anyRequest().authenticated()` deny-by-default?** Any signed-in user, and anyone can sign up, reaches every forgotten endpoint. `denyAll()` last.
4. **Why can't `@RestControllerAdvice` handle a filter-chain 401?** It happens before Spring MVC; an entry point writes the same format.
5. **Your security config started fine but every request was a 500. How?** Patterns are checked per request; a `{…}` list and an endpoint path instead of a name failed on first use.
6. **Is `STATELESS` enough to guarantee no sessions?** No; test that no response sets a cookie.
