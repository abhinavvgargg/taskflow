# Phase 1 — Users & Auth Core (Requirements)

> Companion to `../PROJECT_CONTEXT.md` · decisions and traps from `../phase-0/PHASE_0_LEARNING_LOG.md` · test patterns from `../phase-0/TESTING_GUIDE.md`. Requirements only, no code. Tick the boxes as you go.
> **Time-box: 7h, tests included. Hard stop at 10.5h (150%).** Anything unfinished becomes a side task in Phase 2. The phase doesn't get extended.

**The goal of Phase 1 is identity you can trust.** By the end, a user can register, prove they own their email, log in, get locked out after repeated failures and unlocked automatically, reset a forgotten password and change a known one. Every protected request knows **who** is calling, and every audit column records it. Phase 2 swaps the transport (HTTP Basic → JWT) without touching any of this.

⚠️ **Trap, the one that defines this phase:** security code that "works" in the happy path. A login endpoint that returns 200 for the right password is maybe 20% of the job. The rest is what happens with a wrong password, with an unknown email, when the same token gets used twice at once, and when an attacker compares response times. **Every section below has a test for the failure path, not just the success path.**

---

## Carried in from Phase 0

| Item | What to do in Phase 1 |
|---|---|
| `AuditAwareImpl` returns `"system"` | Replace it with the authenticated user's **username**. §1.2 has the trap. |
| `--debug` auto-configuration report never read | §1.1 makes you read it. Adding the security starter gives you a reason to. |
| "Decide whether echoing `rejectedValue` is safe" | It isn't. Passwords now pass through validation, so **confirm** the handler still never echoes values, and add a test that locks that in. |
| Security-action audit log has no home in the plan | §1.5 gives it one. See decision 6. |
| OpenAPI deferred | **Still deferred.** Do it after Phase 2, so the security scheme gets documented once (bearer) rather than twice (basic, then bearer). When it lands, `/v3/api-docs` and `/swagger-ui` must be allowed through the filter chain in `dev` only. |
| 401/403 bypass `@RestControllerAdvice` (noted in §0.7) | You'll **see** it this phase, and it's still Phase 2's to fix. §1.1. |

---

## Decisions (recorded 2026-09-24)

| # | Decision | Chosen | Options considered · the reasoning |
|---|---|---|---|
| 1 | **How authenticated requests work before JWT exists** | **HTTP Basic, stateless**, plus `POST /auth/login` | Options were Basic, server sessions, or no protected endpoints until Phase 2. Basic lets you build and test `/me` now. The login endpoint is the seam Phase 2 extends to issue tokens, and it teaches you to call `AuthenticationManager` directly. Basic gets deleted in Phase 2. |
| 2 | **Package layout** | **`common/security` + `user`** | `common/security` holds the filter chain config, the principal type and the `PasswordEncoder` bean. `user` holds the entity, repository, `UserDetailsService` implementation, registration, tokens, lockout and the login endpoint. The principal type **has to live in `common`**, because `AuditAwareImpl` in `common/config` reads it and `common` never imports a feature. Same reasoning as the `ErrorCode` interface. |
| 3 | **What the user logs in with** | **Email.** The "either" approach is studied too (see §1.2, *Logging in with either*). | Keep a separate **username** anyway. Phase 0 decision #9 says `created_by` holds a username, and Phase 9 `@mentions` need one. |
| 4 | **Principal type** | **A separate principal record** | The principal lives in the `SecurityContext` for the whole request, outside any transaction. If it's an entity, it becomes a detached entity with lazy associations waiting to throw (Phase 3+), and a stale copy of a row. Carry only id, username, email, password hash, role and the two status flags. |
| 5 | **System roles** | ⏳ **Open. Decide at the start of §1.2.** | Single column (`USER` / `ADMIN`) vs a join table (many roles per user). Discussed in §1.2 before the migration is written. |
| 6 | **Login history table** | **`security_events`** with an `event_type` column | It costs the same as `login_events`, and it closes the Phase 0 "audit log has no home" debt. The login-history endpoint filters it down to login types. |
| 7 | **Registration with an email already registered** | ⏳ **Open. Decide at the start of §1.3.** | 409 `EMAIL_ALREADY_REGISTERED` vs always 202 (plus an email to the existing owner). Discussed in §1.3. Either way, **leave the status out of the registration tests until it's decided**. |
| 8 | **Token storage** | **One `user_tokens` table**, `purpose` constrained by a `CHECK` | The rules (hashed, expiring, single-use, invalidated on reissue) are identical for both purposes. |

---

## Concepts you need first (the *why*, briefly)

💡 **The filter chain, end to end.** Tomcat's filter chain contains one Spring bean, `DelegatingFilterProxy` (bean name `springSecurityFilterChain`). It hands off to `FilterChainProxy`, which picks the first `SecurityFilterChain` whose matcher fits the request and runs **its** ~15 filters in order. Your `CorrelationIdFilter` runs **before** all of this, because it's ordered at highest precedence and security sits at `-100`. That's the Phase 0 filter-vs-interceptor decision, finally paying off. **Why it matters:** every security question you'll be asked ("where does the JWT get validated?", "why didn't my advice catch this 401?") starts with *which filter, in which order*.

💡 **How a password gets checked.** A filter pulls the credentials out of the request and wraps them in an *unauthenticated* `Authentication` → `AuthenticationManager` (implemented by `ProviderManager`) → it asks each `AuthenticationProvider` whether it `supports()` that token type → `DaoAuthenticationProvider` calls your `UserDetailsService`, runs the **pre-checks** (locked? disabled?), checks the password with your `PasswordEncoder`, runs the **post-checks** → returns an *authenticated* `Authentication` → it's stored in `SecurityContextHolder`. Learn this pipeline by heart. Phase 2 adds a JWT filter to it, and Phase 10 adds a provider of your own.

💡 **`SecurityContextHolder` is a `ThreadLocal`.** It's the same mechanism as MDC, and the same pooled-thread hazard. Spring Security clears it at the end of every request (`SecurityContextHolderFilter`). This is the **second** time you've met the ThreadLocal pattern, with Phase 3 and Phase 9 still to come. In Spring Security 6 a context you set by hand is **not** saved between requests unless a `SecurityContextRepository` saves it. That's why stateless means "authenticate on every request".

💡 **Password hashing.** Use a **deliberately slow**, **salted** hash (bcrypt) so that a stolen table costs years to crack instead of seconds. `DelegatingPasswordEncoder` stores `{bcrypt}$2a$10$…`. The `{id}` prefix lets you change algorithms later without a big-bang migration: old hashes keep verifying, and new ones use the new algorithm. **Why it matters:** "how would you migrate 1M users from SHA-1 to bcrypt?" is a real interview question, and the prefix is the answer.

💡 **Secure token design** (email verification, password reset). This is the part tutorials skip. A token needs to be:
1. **Unguessable.** 32 bytes from `SecureRandom`, Base64URL-encoded. Never a UUID. v4 UUIDs happen to be random, but "UUID" doesn't *promise* unpredictability, and 122 bits leaves less margin.
2. **Hashed at rest with SHA-256, not bcrypt.** A leaked database mustn't hand out working reset links. A *fast* hash is correct here, because the input already has 256 bits of entropy, so there's nothing to brute-force. And you need to **look the token up by its hash**, which a salted bcrypt hash can't do.
3. **Expiring**, **single-use**, **purpose-bound** (a verification token can't reset a password), and **invalidated when a new one is issued**.
4. **Kept out of URLs and logs** wherever you control them.

💡 **Account enumeration.** Any response that differs between "this email exists" and "it doesn't" (status, body, *or timing*) lets an attacker test a list of emails against your system. Spring already guards the login path: `hideUserNotFoundExceptions` turns "no such user" into "bad credentials", and `DaoAuthenticationProvider` hashes a dummy password when the user doesn't exist so the timing matches. 🔍 Find both in the source. Your job is to avoid **undoing** that protection anywhere else.

---

## 1.1 — Security starter & the filter chain (~1h)

- [ ] Add `spring-boot-starter-security`, and `spring-security-test` in test scope. **The second isn't part of `spring-boot-starter-test`.**
- [ ] ⚠️ **Create the bug first:** add only the starter, start the app, and call `GET /api/v1/organizations`. You get a 401, and the log shows *"Using generated security password"*. Then run `--debug` (**clearing Phase 0 debt**), find `UserDetailsServiceAutoConfiguration` in the positive matches, and note **which `@ConditionalOnMissingBean` types** will make it back off once you define your own beans. Same lesson as §0.1, but now you'll remember it.
- [ ] One `SecurityFilterChain` bean in `common/security`. Nothing extends `WebSecurityConfigurerAdapter`, which was removed in Spring Security 6.
- [ ] **Stateless:** `SessionCreationPolicy.STATELESS`. 📊 **Check it:** no response carries `Set-Cookie: JSESSIONID`.
- [ ] **CSRF disabled, with the reason written down.** ⚠️ **Trap:** disabling CSRF "because the tutorial did". The honest reason: CSRF works by the browser attaching credentials automatically, and Phase 2's bearer tokens in a header are never attached automatically. Be aware that **browsers do cache and resend Basic credentials**, so Basic is CSRF-exposed in a browser. That's acceptable only because Basic is a temporary, API-client-only bridge.
- [ ] **Access rules, deny by default.** Declare them in this order:

| Request | Access |
|---|---|
| `POST` to each of: `/api/v1/auth/register` · `/api/v1/auth/login` · `/api/v1/auth/verify-email` · `/api/v1/auth/verify-email/resend` · `/api/v1/auth/password-reset/request` · `/api/v1/auth/password-reset/confirm` | `permitAll` (**POST only**; six separate patterns) |
| `GET /actuator/health/**`, `GET /actuator/info` | `permitAll` |
| `/actuator/metrics/**` | role `ADMIN` |
| `/error` | permitted (see the trap below) |
| `/api/v1/**` | authenticated |
| **anything else** | **`denyAll()`** |

- [ ] **Rules are checked top to bottom and the first match wins.** Order them from most specific to most general. ⚠️ Put `/api/v1/**` above `/api/v1/auth/register` and registration is unreachable.
- [ ] Match the actuator paths with Boot's **`EndpointRequest`**, not path strings. It follows `management.endpoints.web.base-path` and a separate management port, so a config change can't quietly open or lock an endpoint.
- [ ] **Leave the security headers on.** Don't touch the headers config. `curl -i` any endpoint and note what you get for free (`X-Content-Type-Options`, `X-Frame-Options`, `Cache-Control`). Disable CSRF on purpose (above) and nothing else.
- [ ] **Scaffolding until §1.2:** test with Boot's generated in-memory user (the password is in the startup log). ⚠️ **Don't** put `spring.security.user.password` in a committed YAML file: that's a committed credential. The `ADMIN` row can only be tested once §1.2 exists.
- [ ] ⚠️ **Trap:** ending with `anyRequest().authenticated()` and calling it deny-by-default. It's **authenticate-by-default**: any endpoint anyone adds anywhere is open to every logged-in user. `denyAll()` at the end forces every new path to be declared on purpose.
- [ ] ⚠️ **Trap: `/error` is secured too.** In Spring Security 6, authorization applies to `ERROR` dispatches. If `/error` isn't permitted, an error that reaches Boot's fallback (like Basic's own `sendError(401)`, or an exception from a filter) comes back as a bare 401 no matter what the real error was.
- [ ] Basic enabled for now. You'll notice the 401 body is **not** your `ProblemDetail`, because it comes from the filter chain, before `DispatcherServlet`. Note it and **don't fix it**. That's Phase 2 (`AuthenticationEntryPoint`).
- [ ] 📊 **Verify the Phase 0 promise:** a 401 still carries `X-Correlation-Id`, and still gets a completion log line. That's the concrete payoff of making the correlation ID a filter rather than an interceptor.
- [ ] 🔍 **Look inside:** at startup, Spring Security logs the ordered filter list for your chain. Copy it into your notes, and find `BasicAuthenticationFilter`, `AnonymousAuthenticationFilter`, `ExceptionTranslationFilter` and `AuthorizationFilter`. Then turn on `logging.level.org.springframework.security=TRACE` for **one** request and follow it through.

🎯 **Interview question:** "What happens between an HTTP request arriving and your controller running, in a Spring Security app?" Answer by naming the filters.

---

## 1.2 — Users, password storage, principal, auditor (~1h)

**Migration `V2__create_users.sql`.** Name every constraint (`pk_`, `uk_`, `ck_`), as in Phase 0.

- [ ] `email`: NOT NULL, **unique**, `varchar(254)` (the maximum length of an SMTP address path), with a `CHECK` that it's already lowercase.
- [ ] `username`: NOT NULL, unique, at most 50 characters (it has to fit `created_by varchar(50)`), with a `CHECK` on the allowed characters. Immutable in this phase.
- [ ] `display_name`, `timezone` (see §1.7).
- [ ] `password_hash`: NOT NULL. ⚠️ Size it for the **longest algorithm you might migrate to, plus the `{id}` prefix**, not for bcrypt's 60 characters. Look up how long an `{argon2}` hash is before choosing.
- [ ] **Role storage: settle decision 5 before writing this migration.** A single column means `role` NOT NULL with a `CHECK` limiting it to your enum values. A join table means `user_roles(user_id, role)` with a composite PK, an FK, and the index Postgres won't create for you (§1.4). It also means loading a collection on **every** authentication, so watch the query count.
- [ ] `email_verified_at timestamptz` (nullable): a timestamp rather than a boolean, because it answers "when?" at no extra cost.
- [ ] `failed_login_attempts` (NOT NULL, default 0, `CHECK >= 0`), `locked_until timestamptz` (nullable), `password_changed_at timestamptz` (NOT NULL). Phase 2 uses `password_changed_at` to reject tokens issued before a password change.
- [ ] Audit columns as in `organizations`.

**Email normalisation, all three validation layers again:**
- [ ] ⚠️ **Trap:** `Alice@x.com` and `alice@x.com` registering as **two accounts**, then neither of them managing to reset their password. Trim and lowercase **in one place**, and apply it on **every** path that accepts an email: register, login, resend, reset request. The DB `CHECK` catches a path that forgot to.

**Entity & enum:**
- [ ] `User` extends `BaseEntity`. Follow the Phase 0 Lombok rules. No public setters on security fields; change them through intention-revealing methods (`markEmailVerified(Instant)`, `changePassword(String hash, Instant)`).
- [ ] ⚠️ **Trap:** `@Enumerated` defaults to `ORDINAL`. Inserting a new role in the middle of the enum silently reassigns every existing user's role. Use `STRING`.
- [ ] Update `@NoArgsConstructor` to `protected`, on `User` from the start (Phase 0 debt).

**Password encoder:**
- [ ] A `DelegatingPasswordEncoder` bean (from `PasswordEncoderFactories`), not a bare `BCryptPasswordEncoder`. Be ready to explain the `{bcrypt}` prefix.
- [ ] ⚠️ **Trap: bcrypt only uses the first 72 bytes.** It's **bytes, not characters**: `@Size(max = 72)` still lets 40 emoji through (160 bytes). Current Spring Security versions **reject** passwords over 72 bytes rather than truncating them silently. 🔍 **Check what your version does**: a unit test that encodes a 73-byte password. Then enforce the limit at the boundary (a custom constraint that counts UTF-8 bytes), so it's a 400 and not a 500.
- [ ] Password policy: a **minimum length** plus the 72-byte maximum. **No composition rules** (NIST SP 800-63B: length beats "1 uppercase + 1 symbol", which just produces `Password1!`). Pick the minimum and justify it.
- [ ] 📊 **Measure it:** time one `encode()` at bcrypt cost 10 (the default) and at 12. Write both down. That number is also why Basic, which runs bcrypt on **every request**, is slow, and a big part of why tokens exist.

**`UserDetailsService` & principal:**
- [ ] Your implementation loads by **normalised email** in a `readOnly` transaction and returns your principal type (decision 4). `isEnabled()` means "email verified". `isAccountNonLocked()` means `locked_until` is null or in the past **according to an injected `Clock`**.
- [ ] 💡 **Production practice: inject a `Clock` bean** (`Clock.systemUTC()`) wherever business logic asks what time it is. **Prevents:** expiry and lockout logic you can only test by sleeping. Tests pass a fixed or adjustable clock instead.
**Logging in with either email or username: study it, don't build it (decision 3).** Be able to explain these four points:
- **The identifier is just a string.** `loadUserByUsername(String)` receives whatever the user typed. "Username" in that method name means *login identifier*, not your `username` column.
- **Resolution has to be unambiguous.** Look up by email if the identifier contains `@`, otherwise by username. ⚠️ **Trap:** if usernames may contain `@`, someone registers username `alice@x.com`, which is **another user's email**. Now one identifier matches two accounts, and whichever query wins decides who gets logged in. The fix is structural: the username `CHECK` **forbids `@`**, so the two namespaces can't overlap. 📌 **Forbid `@` in the username even though you log in by email.** It keeps this option open at no cost.
- ⚠️ **Trap: lockout keyed on the typed string.** If failed attempts are counted per identifier, an attacker alternates `alice@x.com` / `alice` and gets **twice the attempts**. Always resolve to the **user row** and count there. (Your atomic `UPDATE` in §1.5 goes by email. With "either", it would go by user id.)
- **Normalise both** (trim + lowercase) before the lookup, and use the same rules on the registration path.

- [ ] Confirm Boot's generated password is **gone** from the startup log. Your beans made the auto-configuration back off, which is Phase 0 lesson #1 again.

**Auditor:**
- [ ] `AuditAwareImpl` returns the username when the current `Authentication` holds **your** principal, and `"system"` otherwise.
- [ ] ⚠️ **Trap, create it first:** check only `authentication != null && authentication.isAuthenticated()`, then register a user. `created_by` reads **`anonymousUser`**. `AnonymousAuthenticationToken` is present on anonymous requests and **reports itself as authenticated**. Check the *principal type*, not the flag.

🎯 **Interview questions:** "Why bcrypt and not SHA-256 for passwords, but SHA-256 and not bcrypt for reset tokens?" · "How would you migrate every user to a new hashing algorithm?" (hint: `upgradeEncoding` + `UserDetailsPasswordService`, rehash on the next successful login. Implementing it is an **optional stretch**.)

---

## 1.3 — Registration (~45 min)

- [ ] ⏳ **Settle decision 7 first.** It changes the status code, the response body, and whether the duplicate path sends an email.
- [ ] `POST /api/v1/auth/register` with `{email, username, displayName, password}`. It creates an **unverified** `USER`, issues a verification token (§1.4) and sends it. Status per decision 7.
- [ ] Three layers again: the boundary (format, lengths, password bytes), the service (email and username uniqueness), the DB (unique constraints for the race).
- [ ] ⚠️ **Trap: records print their secrets.** A record's generated `toString()` includes **every** component. One `log.debug("Registering {}", request)` and a plaintext password is in your logs. For every request record that holds a secret (password, token), override `toString` to mask it. A unit test asserts the raw value never appears.
- [ ] ⚠️ **Trap: two unique constraints, one generic 409.** In the race, your Phase 0 handler returns `RESOURCE_CONFLICT`, and the client can't tell whether the email or the username clashed. If you want the specific code, translate `DataIntegrityViolationException` **inside the feature**, keyed on the constraint name (no `common`→feature dependency). ⚠️ The nested trap: the INSERT runs at **flush or commit**, which may be *after* your `try` block has returned. The `catch` only works around a call that flushes. You met this in the testing guide §6.3. Or accept the generic 409 and write down why.
- [ ] ⚠️ **Trap, the phantom email:** sending the email **inside** the transaction. If the commit then fails, the user holds a link to an account that doesn't exist. Rule from §4 of the project context: **no remote calls inside transactions.** Send **after the commit**. Simplest way: a non-transactional method calls the transactional one (on **another bean**, remembering the self-invocation trap), then sends. Phase 9 replaces this with `@TransactionalEventListener(AFTER_COMMIT)`.
- [ ] Log the new user's **id**, never the email. Emails are PII.

🎯 **Interview question:** "Does your registration endpoint leak which emails have accounts? Should it?" Defend decision 7 either way, and know that the login and password-reset endpoints **must not** leak it.

---

## 1.4 — Email verification tokens (~1h 15m)

**Email sender (the minimum, since Phase 9 builds the real one):**
- [ ] An `EmailSender` interface in `common`, with **one** implementation active only under the `dev` profile, which logs the message.
- [ ] ⚠️ This is a **deliberate, profile-bounded exception** to "no tokens in logs". Write that down. Under `prod` there's **no** implementation, so startup fails. That's the Phase 0 "fail loudly" principle: a prod deploy mustn't silently drop every verification email. Tests supply their own **capturing** fake (§1.8).

**Migration `V3__create_user_tokens.sql`:**
- [ ] `user_id` (FK, with a deliberate `ON DELETE` choice), `purpose` (`CHECK`), `token_hash` (**unique**, sized for a SHA-256 encoding), `expires_at`, `used_at` (nullable), created audit.
- [ ] ⚠️ **Trap: Postgres does not index foreign-key columns.** It indexes the *referenced* PK, not the *referencing* column. Without `ix_user_tokens_user_id`, "invalidate this user's tokens" and every `ON DELETE` do a sequential scan. 📌 Carry this rule into every FK from now on.

**Rules:**
- [ ] Issue: 32 bytes of `SecureRandom` → Base64URL without padding is the **raw** token (only the email gets it). Store only its **SHA-256**. The expiry comes from typed config (`taskflow.security.tokens.email-verification-ttl`, validated at startup, like `ApiProperties`).
- [ ] Issuing a new token **invalidates** that user's older unused tokens of the **same purpose**.
- [ ] `POST /api/v1/auth/verify-email` with `{token}` → 204. It sets `email_verified_at`, marks the token used and records a `EMAIL_VERIFIED` security event.
- [ ] ⚠️ **Trap: the token goes in the body, never in `?token=` of a GET.** Corporate mail scanners **prefetch GET links**, which consumes a single-use token before the user ever clicks it. Tokens in URLs also end up in access logs, `Referer` headers and browser history. The email links to a frontend page, and that page POSTs the token.
- [ ] Unknown, expired, used and wrong-purpose tokens all return **400 `TOKEN_INVALID`**, one code. Justify it: the client's next step is the same in every case (ask for a new one).
- [ ] ⚠️ **Trap: consuming a token is check-then-act.** "Find the token, check `used_at is null`, set it" lets two concurrent requests both succeed, which matters in §1.6 when it means two password resets. Consume it with **one conditional `UPDATE`** (`… where hash = ? and used_at is null and expires_at > ?`) and branch on the **row count**. It's the same shape of race as the Phase 0 slug, with a different guard: a conditional update instead of a unique constraint.
- [ ] ⚠️ **Trap: two clocks.** If the `UPDATE` compares against the database's `now()` while expiry was computed from the injected `Clock`, a test with a fixed clock breaks, or passes when it shouldn't. **Use one time source**: pass the clock's instant in as a parameter.
- [ ] `POST /api/v1/auth/verify-email/resend` with `{email}` → **always 202**, whether the email exists, is already verified, or neither.
- [ ] Expired tokens pile up in the table. **Don't** build cleanup now. Note it as the Phase 9 `@Scheduled` job candidate.

🎯 **Interview question:** "Design a password-reset token." Answer with all six properties from the Concepts section, and say *why* each one matters.

---

## 1.5 — Login, lockout, login history (~1h 45m)

**Login endpoint:**
- [ ] `POST /api/v1/auth/login` with `{email, password}` → 200 with a user summary (Phase 2 adds tokens to this response). It calls the `AuthenticationManager` **directly**, exposed as a bean from `AuthenticationConfiguration`.
- [ ] 💡 **The same exception, two routes.** An `AuthenticationException` thrown here comes out of your **controller**, so `@RestControllerAdvice` **does** catch it. Map it to a 401 `ProblemDetail`. The *same* exception thrown from `BasicAuthenticationFilter` never reaches the advice (§1.1). Once you've seen both, you'll understand exactly why Phase 2 needs an entry point.

**What the response reveals.** Your decision, with these facts:
- [ ] Unknown email and wrong password → the **same** 401 `AUTHENTICATION_FAILED`, the same body and similar timing. Spring does this for you. Don't undo it with a pre-lookup in your own code.
- [ ] 🔍 Read `AbstractUserDetailsAuthenticationProvider.authenticate`. The **locked** and **disabled** checks run *before* the password is checked. Taken as is, that means anyone can find out that an address is registered-but-unverified **without knowing the password**.
- [ ] 🏗️ Recommended: **locked** stays a pre-check (so a correct password during lockout reveals nothing) and returns the **generic** 401. **Not verified** moves to the **post**-checks (the provider lets you swap both check sets), so only someone who knows the password learns "verify your email first" (403 `EMAIL_NOT_VERIFIED`). Your call, justify it.

**Lockout:**
- [ ] N failed attempts → `locked_until = now + duration`. Both values come from typed, validated config (`taskflow.security.lockout.*`).
- [ ] Auto-unlock is **lazy**: nothing runs on a schedule. The account simply counts as unlocked once `locked_until` has passed (you built that into §1.2). A successful login resets the counter. 🏗️ Does the counter restart after a lock expires, or does one more failure re-lock straight away? Decide.
- [ ] **Lockout applies to every credential check**, so it covers Basic-authenticated requests too, not just `/auth/login`. ⚠️ **Trap:** putting the counter in the login controller leaves **Basic as an unthrottled side door** for brute force. Drive it from **authentication events** instead: `AuthenticationFailureBadCredentialsEvent` and `AuthenticationSuccessEvent`, which fire whichever path did the checking.
- [ ] ⚠️ **Trap: the silent listener.** Boot auto-configures a `DefaultAuthenticationEventPublisher`, and the managers Spring Security builds pick it up. **A `ProviderManager` you create with `new` has a no-op publisher**, and your listener never fires. Prove it fires, on both paths, with a test.
- [ ] ⚠️ **Trap, the main one of this phase. Create it first:** mark the login flow `@Transactional`, and write the failure counter in that same transaction. Authentication throws → the transaction **rolls back** → **the counter never increments** → lockout never happens, and nothing errors. The counter update needs its **own** transaction, which is Phase 7's `REQUIRES_NEW` showing up early. Or keep authentication out of any transaction. Either way, the proof is an integration test: N wrong passwords, then the **correct** password → still rejected.
- [ ] ⚠️ **Trap: lost updates.** Two concurrent failures both read `3` and both write `4`. Increment **in the database**, atomically: one `UPDATE … set failed_login_attempts = failed_login_attempts + 1`. No read-modify-write in Java. Know the side effect: a JPQL bulk update **bypasses the persistence context and JPA auditing**, so `updated_at` doesn't move. For a failed-login counter that's arguably right. (Preview of the deferred bulk-operations topic.)
- [ ] An attempt against an **unknown** email has no row to count. Fine: per-IP throttling is Phase 10's rate limiter.
- [ ] 🎯 **Lockout is a denial-of-service tool.** Anyone who knows your email can lock you out. Auto-unlock limits the damage, and per-IP limits (Phase 10) are the real fix. Have this answer ready. You'll be asked.

**Security events and login history (decision 6):**
- [ ] Migration `V4__create_security_events.sql`: `user_id` (FK, **indexed together with `occurred_at`** in the order the history query sorts), `event_type` (`CHECK`), `occurred_at`, `ip_address`, `user_agent` (truncated to a chosen length).
- [ ] 🏗️ This table is itself an audit record and is only ever appended to, so does it need `updated_at` / `updated_by`? Extend `BaseEntity`, or map id + `occurred_at` only. Decide.
- [ ] Record `LOGIN_SUCCEEDED` / `LOGIN_FAILED` / `ACCOUNT_LOCKED` **from `/auth/login` only**. ⚠️ **Trap:** recording from the authentication events writes a "login" for **every Basic-authenticated request**. That's why the counter follows the events but the history doesn't. Write down the asymmetry and the reason for it.
- [ ] ⚠️ **Trap: `X-Forwarded-For` is client-controlled.** Read `request.getRemoteAddr()`. Only trust forwarded headers when a known proxy is in front (`server.forward-headers-strategy`, not enabled now). Otherwise anyone can write any IP into your audit trail.
- [ ] IP addresses are **personal data** under GDPR. Write down a one-line retention stance. Don't build it.
- [ ] `GET /api/v1/users/me/login-history` → paginated with your Phase 0 machinery, sorted by `occurred_at desc`, then `id desc` (**stable sort**).
- [ ] 💡 **The first ownership rule in the codebase:** there is **no user id in the path**. The service takes the id **from the principal** (`@AuthenticationPrincipal`), never from the request. "Never trust a client-supplied ID" from §4 of the project context, made concrete, and the seed of Phase 4.

---

## 1.6 — Password reset & password change (~1h)

**Reset (the user is anonymous):**
- [ ] `POST /api/v1/auth/password-reset/request` with `{email}` → **always 202**. Unknown email, unverified email, locked account: every case gets the identical response.
- [ ] ⚠️ **Timing still leaks:** a known email does a DB write and sends an email, and an unknown one returns straight away. **Note it; don't fix it.** Phase 9's async sending removes most of the gap. Have the answer ready.
- [ ] Token rules as in §1.4, with purpose `PASSWORD_RESET` and a **much shorter** TTL, separately configured. Justify the number.
- [ ] `POST /api/v1/auth/password-reset/confirm` with `{token, newPassword}` → 204, all in **one transaction**: consume the token (conditional `UPDATE`), set the new hash, stamp `password_changed_at`, **invalidate every other outstanding reset token** for that user, and record `PASSWORD_RESET`.
- [ ] 🏗️ Does a successful reset **also clear the lockout**? (Recommended: yes. Proving you own the email is stronger evidence than the lockout guards against.) Does it **also verify** an unverified email? Decide both.
- [ ] 📊 **Race it**, as with the Phase 0 slug: fire 20 concurrent confirms with the same token. **Exactly one** 204, the rest 400, zero 500s.

**Change (the user is authenticated):**
- [ ] `PUT /api/v1/users/me/password` with `{currentPassword, newPassword}` → 204. **Re-authentication:** a valid Basic header isn't enough, so `currentPassword` must match. The new password has to differ from the current one. Stamp `password_changed_at` and record `PASSWORD_CHANGED`.
- [ ] 🏗️ **What status for a wrong `currentPassword`?** ⚠️ **Not 401.** A 401 tells the client "you are not authenticated", and a well-behaved client (Phase 2) throws away its tokens and logs the user out. The user *is* authenticated; they got one field wrong. Pick 400 or 403, and justify it. Does a wrong current password count toward lockout? Decide.

🎯 **Interview question:** "After a password change, what happens to the user's other logged-in devices?" Today: nothing, and you know that. Phase 2: every token issued before `password_changed_at` gets rejected. That's why the column exists now.

---

## 1.7 — Profile (~30 min, **first thing to trim**)

- [ ] `GET /api/v1/users/me` → id, email, username, displayName, timezone, role, emailVerified, createdAt. It **never** includes the password hash, the lock state or failed attempts. The response DTO decides what's public, which is Phase 0's over-exposure argument with a real hash behind it now.
- [ ] `PATCH /api/v1/users/me` with `{displayName?, timezone?}`, where `null` means "leave unchanged". 💡 A record **can't tell "field missing" from "field sent as null"**. Write that limit down: a real PATCH needs JSON Merge Patch or `JsonNullable`. Not now.
- [ ] `timezone` must be a valid IANA **region** ID. ⚠️ **Trap:** `ZoneId.of` also accepts `+05:30`, `UTC+1` and `Z`. Fixed offsets are *not* timezones, because they ignore daylight saving. Validate against `ZoneId.getAvailableZoneIds()`.
- [ ] Email change is **out of scope**: it needs re-verification of the new address, and it's a whole feature.

---

## 1.8 — Tests (you write these; estimates above include them)

Patterns come from `TESTING_GUIDE.md`. What's **new** this phase:

**New tools:**
- **`spring-security-test`**: `@WithMockUser`, `@WithUserDetails`, and the MockMvc post-processors `with(httpBasic(…))` / `with(user(…))` / `with(anonymous())`.
- **An adjustable `Clock`**, registered in test configuration, so a test can "move forward 31 minutes" without sleeping.
- **A capturing `EmailSender`** test bean that records sent messages. Integration tests take the raw token out of it, which is exactly how a real user gets it.

⚠️ **Existing tests will break the moment the starter lands.** Expect it; don't `@Disable` anything.
- `OrganizationControllerTest`: a `@WebMvcTest` slice may **not** load your `SecurityFilterChain`, because it's a `@Bean` inside a `@Configuration`, not a scanned filter. It then gets Boot's **default** chain instead, which gives **401** on GET and **403 on POST (CSRF!)**. Check which one you're getting. The fix is to `@Import` your security config and supply whatever beans it needs (`@MockitoBean` for the `UserDetailsService`).
- `OrganizationApiIntegrationTest`: now 401. Create a verified user in setup and use `restTemplate.withBasicAuth(…)`.
- 📌 **Observed 2026-09-24 (starter only, no config yet): 6 of 13 tests fail.**
  - The slice gives GET 401, POST 403.
  - The integration test gives GET 401, POST **401 or 302**, not 403. It's the same CSRF rejection, but real Tomcat runs an `ERROR` dispatch to `/error`, the default chain secures `/error`, and the anonymous caller gets the entry point instead. The 302 is the default chain's **form-login** entry point redirecting to `/login`.
  - MockMvc never runs the `ERROR` dispatch, so the slice shows the raw 403. **Same cause, different status, depending on the kind of test.**

**Required tests. At least one per rule, and each must fail when its bug is planted** (the testing guide's mutation check):

| Kind | Must prove |
|---|---|
| Unit | Email normalisation · token hashing (the raw value never equals the stored value) · lockout threshold and expiry **using the adjustable clock** · the request record's `toString` never contains the password · the 72-byte boundary (72 passes, 73 → rejected) |
| Web slice | Anonymous `GET /users/me` → 401 · the register validation 400 **does not echo the password** anywhere in the body · the `/auth/*` endpoints are reachable anonymously, and **only** with POST |
| JPA slice | Uniqueness of the lowercased email · `CHECK` rejects an uppercase email (flush it! guide §6.3) · the atomic counter increment · the conditional token-consume UPDATE returns 0 on its second run · auditing writes the **username** as `created_by` (and `"system"` for an anonymous registration) |
| Integration | Register → capture the token → verify → login 200 · login **before** verification → the response you chose · **N bad passwords, then the correct one → still 401** (the rollback trap) · the same through Basic (the side door) · move the clock past `locked_until` → login works · reset token used twice → second is 400 · reset for an unknown email → 202 and **no** email captured · `GET /organizations` anonymously → 401, with a user → 200 |
| Security | One test per row of the §1.1 access table. `/actuator/metrics` with a `USER` → 403, with an `ADMIN` → 200. |

📊 **Measure:** suite time and container count before and after this phase. bcrypt plus a second full context can quietly double both. If a new context appears, work out **which** configuration difference caused it (guide §7).

---

## Definition of done

Check these literally, by running them.

- [ ] No generated password in the startup log. The `--debug` report has been read, and the backing-off condition written down.
- [ ] Register → the verification link is in the dev log → verify → login 200. The `users` row shows a `{bcrypt}` hash, a lowercase email, and `created_by` = `system`.
- [ ] Login before verification behaves exactly as decided in §1.5.
- [ ] N wrong passwords → locked; the correct password is still rejected; after the lock duration it works. **Also tested through Basic.**
- [ ] Unknown email and wrong password give byte-identical response bodies (except `timestamp` and `correlationId`).
- [ ] Reset for an unknown email and for a known email → both 202 with identical bodies.
- [ ] A reset token works once. The second use and the concurrent race both give exactly one success.
- [ ] No password, token or email appears anywhere in the logs at INFO (the `dev` email sender is the one documented exception).
- [ ] Every row of the access table behaves as specified. The last rule is `denyAll()`.
- [ ] No `JSESSIONID` cookie in any response. 401s carry `X-Correlation-Id`.
- [ ] After a password change, `password_changed_at` has moved, and a `PASSWORD_CHANGED` event exists.
- [ ] Suite green; the Phase 0 tests are fixed, not disabled.
- [ ] README updated: the auth endpoints, how to register and verify in dev (where the link appears), and the Basic auth note.
- [ ] Small commits, roughly one per section.

---

## Deliberate failures: create each bug, see it, then fix it

These go into section 7 of your learning log. They're interview stories, not theory.

| Break | Expect |
|---|---|
| Starter added, no beans | Every endpoint 401; a generated password in the log |
| Grant `"ADMIN"` as a plain authority, check with `hasRole("ADMIN")` | **403** for a real admin. `hasRole` adds the `ROLE_` prefix itself |
| Failure counter written inside the login transaction | Counter stays 0; lockout never happens; no error anywhere |
| `new ProviderManager(…)` without a publisher | Listener never fires; lockout never happens |
| Auditor checks only `isAuthenticated()` | `created_by = anonymousUser` |
| `log.debug("{}", registerRequest)` | Plaintext password in the log |
| Token consumed with read-then-write | The 20-way race gives more than one 204 |
| 73-byte password with no byte check | A 500 (or silent truncation, depending on version: find out which) |

---

## Explicitly NOT in Phase 1

JWT, refresh tokens, logout, revocation · JSON 401/403 from the filter chain (`AuthenticationEntryPoint`, `AccessDeniedHandler`) · CORS · method security (`@PreAuthorize`, Phase 4) · org or project roles · OAuth2 / social login / MFA · remember-me, server sessions · email change, account deletion · real SMTP, async sending, token cleanup job (Phase 9) · per-IP rate limiting, CAPTCHA (Phase 10) · breached-password checking (know the HIBP k-anonymity idea for interviews, don't build it) · OpenAPI.

---

## 🎯 Interview questions: answer these *during* Phase 1

1. Walk me through what happens when a request with a Basic header reaches your app, filter by filter.
2. `AuthenticationManager` vs `AuthenticationProvider` vs `UserDetailsService`: what does each one do?
3. How is a password stored in your system? Why bcrypt? What's the `{bcrypt}` prefix for?
4. Why SHA-256 for reset tokens but bcrypt for passwords?
5. Design a password-reset flow. What makes the token secure?
6. How does your login avoid user enumeration, including timing? Where else could enumeration leak?
7. How does your lockout work under concurrent requests? Why isn't the counter lost when authentication fails?
8. Isn't account lockout a denial-of-service vector? What would you add?
9. `hasRole` vs `hasAuthority`: what's the difference?
10. Where is the `SecurityContext` stored, and why must it be cleared?
11. `anyRequest().authenticated()` vs `denyAll()`: which is deny-by-default?
12. Why is disabling CSRF safe for your API, and when would it *not* be?
13. Why can't `@RestControllerAdvice` handle every `AuthenticationException`?
14. How do you test time-dependent logic like token expiry and lockout?

---

## Session split (~7h)

| Session | Sections | Est. | Actual |
|---|---|---|---|
| 1 | 1.1 + 1.2: starter, filter chain, users schema, encoder, `UserDetailsService`, auditor. Fix the Phase 0 tests. | ~2h | |
| 2 | 1.3 + 1.4: registration, email sender, verification tokens | ~2h | |
| 3 | 1.5: login, lockout (the rollback and event traps), security events | ~1h 45m | |
| 4 | 1.6 + 1.7: reset, change, profile, README | ~1h 30m | |

Over budget? **Trim in this order:** §1.7 profile → the login-history *endpoint* (keep **recording** the events) → the password-change endpoint. **Never trim:** hashed single-use tokens, the lockout rollback test, the access-rule tests.

**Before session 1:** re-read the Spring Security reference pages *Servlet Architecture* and *Authentication Architecture* (the plan's week-0 reading). The Concepts section above assumes them.

---

## Phase 1 notes (15 min at the end; these become interview stories)

**What I built:**

**What confused me:**

**What I learned:**

**Traps I actually hit:**
