# Security 6. CORS and CSRF

> Two different browser problems that the refresh cookie brought back in Phase 2. Sources: [../SOURCES.md](../SOURCES.md).

## CORS vs CSRF

- **CORS** controls whether a script on another origin may **read** a response (and send credentials). It's the browser protecting the user's data from other sites' scripts.
- **CSRF** is a forged request riding on credentials the browser attaches **by itself** (cookies, cached Basic credentials). CORS doesn't stop it: the request is sent either way.

## CSRF

- **Off for the API, with the reason written in the config:** bearer tokens are never attached by the browser; the client's code sets the header on each call.
- **The two endpoints that read the refresh cookie (`/auth/refresh`, `/auth/logout`) are protected three ways:** `SameSite=Strict`; the cookie's `Path=/api/v1/auth`; and an **`Origin` allow-list check**.
- **`SameSite=Strict` stops other sites, not sibling subdomains** (same site, different origin): the `Origin` check closes that.
- **Check `Origin` before reading the cookie,** so a foreign page can't even burn the token (*seen*, §2.3 #6: 403, token unconsumed).
- **An absent `Origin` is allowed:** non-browser clients send none, and CSRF needs a browser, which always sends `Origin` on a cross-origin POST.

## CORS

- **CORS must run inside the security chain** (`http.cors(...)`): preflights (`OPTIONS`, no credentials) would otherwise hit `authenticated()` and get 401 (*seen*, §2.5 #1: 401 with `WWW-Authenticate: Bearer`). Configured only in Spring MVC, it comes too late.
- **Pass the source explicitly:** `CorsConfigurer` without one looks up a bean named exactly `corsConfigurationSource`.
- **First registration wins:** `UrlBasedCorsConfigurationSource` returns the first matching pattern, so the narrow `/api/v1/auth/**` (with credentials, for the cookie) is registered before `/api/v1/**` (*seen*, §2.5 #2: reversed, the refresh preflight lost `Allow-Credentials`).
- **Exact origins, validated at startup** (`CorsProperties`): credentials with `*` throws in Spring, and TaskFlow refuses `*` at startup naming the property (*seen*, §2.5 #3). Per-tenant subdomains would need wildcard origin patterns with credentials, which this design refuses (one reason Phase 3 D3 rejected subdomain tenancy).
- **`CorsFilter` also rejects disallowed *actual* requests,** not only preflights: a plain-text `403 Invalid CORS request` before any of our code (*verified* in spring-web 6.2.19; *seen* on refresh and logout). It's the one error that isn't `ProblemDetail` (Phase 2 D15, accepted: browsers can't read a failed CORS response, and `CorsConfigurer` has no processor hook). Same-origin requests aren't CORS requests and pass; `AllowedOrigins` stays as the backstop.
- **A plain `OPTIONS` (not a preflight) does reach MVC** and Spring answers it itself; see the tenant gate's `OPTIONS` lesson ([spring 03](../spring/03-filters-interceptors-and-request-scope.md)).

## Interview questions

1. **CORS vs CSRF?** CORS controls whether another origin's script may read a response; CSRF is a forged request on credentials the browser attaches. SameSite + `Origin` handle CSRF here; CORS doesn't.
2. **When is it safe to disable CSRF?** When credentials aren't attached automatically: bearer tokens yes, cookies no.
3. **Why must CORS be configured in the security chain?** Preflights carry no credentials and would hit the authentication rule first.
4. **Your refresh cookie is `SameSite=Strict`. Why also check `Origin`?** Strict stops other sites, not sibling subdomains.
