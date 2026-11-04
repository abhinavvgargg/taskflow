# Spring 3. Filters, interceptors and request scope

> Where code can run around a request, which point has what information, and how per-request state is held without leaking into the next request. Built on the correlation-id filter (Phase 0) and the tenant gate (Phase 3). Sources: [../SOURCES.md](../SOURCES.md).

## Three places to run code around a request

| | Runs | Knows | Its exceptions | TaskFlow uses it for |
|---|---|---|---|---|
| **Servlet `Filter`** (`OncePerRequestFilter`) | Around everything, before Spring Security and MVC | The raw request | Bypass `@RestControllerAdvice` | Correlation id (Phase 0) |
| **Spring Security filter / `AuthorizationManager`** | Inside the security chain | The principal (after authentication) | Go to the entry point / access-denied handler | Authentication, URL rules ([security 01](../security/01-filter-chain-and-access-rules.md)); method security is Phase 4 |
| **`HandlerInterceptor`** | After the controller is chosen and path variables parsed | Principal **and** path variables, the handler method | Go through `@RestControllerAdvice` | The tenant gate (Phase 3) |

**Pick the latest point that has everything you need.**
- The **correlation id had to be a filter, ordered first:** an interceptor never runs when security rejects a request, so 401s and 403s, the responses you most need to trace, would have no id. *Seen:* a 401 carries `X-Correlation-Id`.
- The **tenant gate had to be an interceptor:** it needs the caller (after security) and `{orgId}` (after routing). A filter would parse URLs by hand and disagree with the router on edge cases; a check in every service gets forgotten once. Its exceptions become the normal `ProblemDetail` because `DispatcherServlet.doDispatch` calls `applyPreHandle` inside the `try` that feeds `processDispatchResult` (*verified*, spring-webmvc 6.2.19).

## Writing a filter

- **`OncePerRequestFilter`, not a plain `Filter`:** a plain filter can run again on an internal `FORWARD`/`ERROR` dispatch (two ids for one request).
- **Validate client-supplied values** (length cap, character whitelist): an unbounded or newline-carrying correlation id is a log-injection and log-flooding vector.
- **Clear what you set, in `finally`.** Tomcat pools threads; an MDC value not cleared is stamped on the next, unrelated request's logs: debugging with actively wrong evidence.
- **Know how it's registered and ordered** (`@Component` + `@Order`, or a `FilterRegistrationBean` with explicit order and URL scoping).

## Writing an interceptor: the tenant gate

- **Path variables:** `RequestMappingInfoHandlerMapping.handleMatch` stores them in the request attribute `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE` during handler lookup, before `preHandle` (*verified*).
- **Registration patterns decide where it runs:** `/api/v1/organizations/*` and `/api/v1/organizations/*/**`, not `/organizations/**`, because the collection (create, "my organizations") has no `{orgId}`. *Seen (deliberate failure):* registered for `/**`, "my organizations" and create became 500, and two unrelated security tests failed.
- **Read the variable by name and fail closed:** a controller mapped `/{id}/…` gives no `orgId`; skipping would let anyone into any organization through it. *Seen (deliberate failure):* the skipping gate let a stranger get 200. Missing `orgId` is a 500 (a programming error, loud in the first test).
- **Gate our controller methods, all of them, and nothing else.** The predicate is "a `HandlerMethod` whose bean type is a `@Controller`" (`AnnotatedElementUtils.hasAnnotation(beanType, Controller.class)`), exactly `RequestMappingHandlerMapping.isHandler`'s; `HandlerMethod` stores the user class, so proxied controllers stay gated (*verified*). Two kinds of handler run none of our code: an unmapped path ends in the static-resource handler (404); an **unmapped `OPTIONS`** is answered by a `HandlerMethod` Spring builds in `handleNoMatch` (`HttpOptionsHandler`), where **no path variables are stored**. *Hit (review, 2026-10-16):* the first gate checked `instanceof HandlerMethod`, and `OPTIONS /api/v1/organizations/{id}` was a 500 with an ERROR stack trace for any signed-in user, at will. Skipping every `OPTIONS` was rejected: an `OPTIONS` mapping we write must stay gated (a test pins it).
- **Non-numeric id → the same 404,** not a 400: another id that names no organization.

## Request-scoped state: `TenantContext`

- **`@RequestScope`** makes Spring create one instance per HTTP request and discard it when the request ends. Singletons receive a **scoped proxy** (a CGLIB subclass, so the class is non-final with a no-arg constructor) that, on each call, finds the current request's instance through `RequestContextHolder`, itself a `ThreadLocal` Spring sets and clears around each request.
- **Why not a static `ThreadLocal` holder (D5):** any path that skips the `clear()` (an exception before the interceptor stack completes, an early `return false`, a second `DispatcherServlet`, code added outside the interceptor) leaves the previous request's tenant on a pooled thread, and **the next request on that thread runs as another organization**: silent, intermittent, load-dependent. With `@Async`, a plain `ThreadLocal` is empty in the pool thread; an `InheritableThreadLocal` is worse (pool threads keep whichever tenant created them).
- **Outside a request it fails loudly:** `ScopeNotActiveException` (*verified* in `AbstractBeanFactory`). *Seen (§3.1 deliberate failure 5):* an `@Async` method reading `TenantContext` threw it on `task-1` ("Scope 'request' is not active for the current thread"), and the request became a 500. Background work (Phase 9) must be handed ids explicitly; the Hibernate resolver (§3.4) must check for an active request first ([jpa 08](../jpa/08-multi-tenancy-with-hibernate.md)).
- **Set once, read everywhere:** `enter` may be called once per request (a second call throws); reading before `enter` throws (a service reached without the gate is a bug). Services read the organization **only** from the context, never from a path variable or body: equal today, but the day another value is used, the gate checked one id and the query uses another.
- **The same `ThreadLocal`-plus-pooled-thread hazard** appears three times in this project: MDC (Phase 0), the tenant (Phase 3), the security context and `@Async` (Phase 9). Learn it once.

## In tests

`@WebMvcTest` includes `HandlerInterceptor` and `WebMvcConfigurer` beans (*verified* in spring-boot-test-autoconfigure 3.5.16), so **every web slice loads the gate**: slices need a `@MockitoBean MembershipLookup` and `@Import(TenantContext.class)`, and must authenticate with our principal type ([testing 01](../testing/01-test-strategy-and-slices.md)).

## Interview questions

1. **Filter or interceptor?** The latest point with what you need: a filter for the correlation id (it must run even when security rejects), an interceptor for the tenant gate (it needs the principal and parsed path variables, and its errors should use the normal error format).
2. **Why a request-scoped bean instead of a `ThreadLocal`?** Spring creates and discards it with the request, so nothing survives on a pooled thread; outside a request it throws instead of returning stale data.
3. **Why did a plain `OPTIONS` request cause a 500?** Spring answered it with its own handler method, which stores no path variables, and the gate failed closed on the missing `orgId`; the gate now checks for our `@Controller` methods.
4. **Why must the MDC be cleared in `finally`?** Threads are pooled; a leftover value tags the next request's logs.
