# Phase 1 — Learning Log (Users & auth core)

> **Phase closed 2026-10-01.** This log is organised by **theme**, and every lesson explains itself: what happens, what happened to us, and the rule we follow now. For the detail of a single sub-section (its brief, requirements, deliberate failures and test plan), see `PHASE_1_REQUIREMENTS.md`.
> Related: `SECURITY_TESTING_GUIDE.md` (how the access rules are tested) · `../phase-0/PHASE_0_LEARNING_LOG.md` (the foundations).

**How to read the labels:**
- *What happened to us* means it really happened in this project: a bug we hit, or one caught in review before it shipped.
- *Checked in the source* means I read the Spring / Boot / Hibernate source in `~/.m2` rather than relying on memory.
- *Tested on a copy* means an experiment on a scratch copy of the project, never on your working tree.

---

## 1. What was built

By the end of Phase 1, a person can sign up, prove they own their email address, sign in, get locked out after repeated wrong passwords (and unlocked automatically), reset a forgotten password and change a known one. Every request knows who is calling, and security-relevant actions are recorded in a log that can't be edited.

| Capability | What it does | Main classes |
|---|---|---|
| **The gate** | Every request passes through Spring Security. Six sign-up/sign-in endpoints are public; everything else under `/api/v1` needs credentials; any path nobody declared is refused. For now, credentials are HTTP Basic (email + password on every request); Phase 2 replaces that with JWT. | `TaskflowSecurityConfig` |
| **Accounts** | A `user_accounts` table with database rules that catch bad data, passwords stored with bcrypt, and a small "who is calling" object (the principal) that every request carries. Audit columns record the caller's username. | `UserAccount`, `TaskflowUserDetailsService`, `TaskflowPrincipal`, `AuditAwareImpl` |
| **Registration** | `POST /auth/register` creates an unverified account (201). A duplicate email or username gets a 409 that names the field, even when two people race for it. | `UserRegistrationService` |
| **Email verification** | Registration emails a one-time link. `POST /auth/verify-email` verifies the account; `/verify-email/resend` sends a fresh link and always answers 202. | `UserTokenService`, `RegistrationWorkflow`, `AccountEmails` |
| **Login** | `POST /auth/login` returns 200 with the account. Unknown email, wrong password and locked account all get the same 401. An unverified account gets 403, but only if the password was right. | `LoginService` |
| **Lockout** | Five wrong passwords lock the account for 15 minutes, whether they came through `/auth/login` or a Basic header on any request. | `AuthenticationEventsListener`, `LoginAttemptService` |
| **Security log** | Sign-ins, failures, lockouts, verifications, resets and password changes are written to `security_events`, which the database refuses to update. Users can read their own sign-in history at `GET /users/me/login-history`. | `SecurityEvent`, `SecurityEventRecorder`, `LoginHistoryService` |
| **Password reset** | `request` always answers 202 and emails a 30-minute link to the address on file. `confirm` sets the new password, unlocks the account, verifies the email if needed, records it, and emails the owner. | `PasswordWorkflow`, `PasswordService` |
| **Password change** | `PUT /users/me/password` asks for the current password (checked like a login, so wrong guesses count toward lockout), cancels any outstanding reset link, records it, and emails the owner. | same |

**Not built:** the profile endpoints (§1.7, moved to Phase 2), changing your email (in the backlog, `PROJECT_CONTEXT.md` §6), OpenAPI docs (after Phase 2).

**Commits:** `9211684` (§1.1) · `1fc0f54` (§1.2) · `02bc36b` (§1.3) · `ece675b` … `5085ed7` (§1.4) · `aa7efca`, `a2cd682` (§1.5) · `63bb1b4`, `4e115a2` (§1.6), plus documentation commits.

### How we know it works

- **The test suite: 76 tests, all passing** (run on a copy of the project on 2026-10-01, ~13 seconds, 2 database containers). But it only covers §1.1–§1.2 and Phase 0. **Nothing from registration onwards has automated tests yet**; that's recorded as debt (section 8), and we decided not to write them before Phase 2.
  - How the suite grew: 13 tests → 45 after §1.1 → 76 after §1.2 (9.4 seconds). New test classes reused the already-started test contexts, so the security integration test ran 13 tests in 0.44 seconds without starting a second application.
- **Things I checked directly:** the framework behaviours described below (in the source or on a copy); the V4 migration in a database transaction that was rolled back afterwards; and the §1.4 resend race in the dev database (10 simultaneous requests → 4 links issued, 6 detected the collision and answered 202 without sending a duplicate email).
- **Things you ran by hand and reported:** registration, verification and resend (including both races); lockout through both paths; deliberate failures 1 and 3 of §1.5; and the full §1.6 checklist, including 20 simultaneous attempts to use one reset link. Exact numbers weren't always sent.

---

## 2. Decisions

These are the choices that shaped Phase 1, grouped by theme. The dated versions, with the alternatives we rejected, are in the Decisions table of `PHASE_1_REQUIREMENTS.md`.

### Who a user is

- **People sign in with their email, but the app also gives them a username.** The username (lowercase, never containing `@`) is what goes into `created_by` columns, so audit history survives an email change, and an email can never be mistaken for a username.
- **The table is `user_accounts`, the entity `UserAccount`.** `user` is a reserved word in Postgres, and `User` is already a Spring Security class.
- **A user has one platform role, `USER` or `ADMIN`, in a single column.** "Many roles per user" belongs to organisations and projects (Phases 3–4), which get their own tables.
- **The signed-in user is represented by a small separate class, `TaskflowPrincipal`, not by the entity.** The principal lives for the whole request, outside any database transaction; an entity there would be a stale copy with lazy fields waiting to throw. It sits in `common/security` because the auditor (also in `common`) needs to read it, and `common` never imports a feature package.
- **Emails and usernames are accepted in any case and stored lowercase**, normalised in one place, so `Alice@X.com` and `alice@x.com` can't become two accounts.

### The gate

- **HTTP Basic, with no sessions, until Phase 2.** It let us build and test signed-in endpoints before JWT exists; it gets deleted in Phase 2.
- **The health endpoint is public, but its details are for admins only.** Kubernetes-style probes call it anonymously; the details (database type, disk paths) are useful to attackers.

### Passwords

- **At least 12 characters, at most 72 bytes, and no "must contain a symbol" rules.** Length is what makes a password strong; 72 bytes is bcrypt's real limit. The rule lives in one annotation, `@ValidPassword`, used by registration, reset and change.
- **The login form and "current password" field only check that something was typed and that it fits in 72 bytes**, never the minimum length, so tightening the rule later can't lock out people with older passwords.

### Registration

- **A duplicate email gets a 409 that says so.** This knowingly reveals that the email is registered, because it's much clearer for someone who forgot they have an account. Login, resend and reset never reveal it.
- **Registration returns 201 with the new account, but no `Location` header,** because there's no URL where the caller could read another user.
- **When two people race for the same email,** the database's unique constraint stops the second, and the service turns that into the same 409 as the normal case.

### Links in emails (tokens)

- **One `user_tokens` table** for both verification and reset links, since the rules are the same.
- **Asking for a new link cancels the old one,** and the database guarantees at most one live link per user and purpose.
- **The token travels in the part of the URL after `#`,** which browsers never send to a server.
- **Verification links last 24 hours, reset links 30 minutes.** A reset link is effectively the key to the account, so it gets the shorter life.
- **Emails are sent only after the database change has been saved,** from a separate class that has no transaction. If sending fails, we log the account id and the request still succeeds; the user can ask for a new link.

### Login and lockout

- **A locked account gets the same 401 as a wrong password; an unverified account gets 403, but only after the right password.** Section 3 explains why the order of these checks matters.
- **Five wrong passwords lock the account for 15 minutes, and the counter starts again from zero after a lock.** Only wrong passwords count. Lockout exists to slow guessing on one account; throttling the attacker is Phase 10's rate limiter.
- **The lockout counter listens to Spring Security's authentication events** rather than living in the login endpoint, so a password checked through Basic counts too. It writes in its own transaction and fails loudly if it can't write.
- **There's exactly one password-checking component (`DaoAuthenticationProvider`), declared as a bean,** so Basic and `/auth/login` share the same rules and the same events.

### The security log

- **`security_events` stores IPs as Postgres `inet`, allows a fixed list of event types, records a reason only for failed sign-ins, stores nothing for emails that have no account, and is deleted along with the user.** We'll keep events for 12 months (a stance for now; Phase 9's cleanup job will enforce it).
- **The log can't be edited:** the entity is marked `@Immutable`, and a database trigger rejects any `UPDATE`.
- **An event is saved in the same transaction as the thing it records,** except for failed attempts, which are saved on their own so they survive the failure.

### Reset and change

- **A password reset also unlocks the account and verifies the email**, because clicking a link sent to the mailbox proves more than a guessed password ever could.
- **After a reset or a change, the owner gets a "your password was changed" email,** so a takeover doesn't go unnoticed.
- **A wrong current password gets 400, not 401, and counts toward lockout.** A 401 would make a Phase 2 client think the user was signed out.
- **`UserAccount` has `@DynamicUpdate`** (section 3 explains why).

### Process

- **Tests for §1.3–§1.6 are deferred, and none will be written before Phase 2.**
- **§1.7 (profile) moved to Phase 2** as a side task, because Phase 1 ran past its time budget.
- **Changing your email went to the backlog,** with its design written down.
- **`LoginFailedException` lives in `common/error`:** your choice, and a better one than my brief's, since it isn't specific to users.

---

## 3. Lessons, by theme

### 3.1 How Spring Security decides who gets in

*Spring Security is a chain of filters that runs before your controllers. Most of §1.1's problems came from rules that looked right and weren't, and the app gave no sign until a request arrived.*

**A security config that starts cleanly can still be broken**

Spring Security checks the URL patterns in your rules only when a request reaches them, not when the app starts. So a typo in a rule gives a perfectly normal startup, and then errors on real traffic.

*What happened to us:* we wrote the six public paths as `"/api/v1/auth/{register, login, …}"`, thinking it was a list. In Spring, `{…}` means "capture this part of the path into a variable", and a comma isn't allowed in a variable name. Because that was the first rule, **every POST in the app returned a 500**. A second mistake, `EndpointRequest.to("health/**")`, which expects an endpoint *name* like `"health"`, not a path, made **every GET return a 500**. Both started without a single warning.

*The rule:* after changing the security config, test every rule, not just the one you touched. The access table now lives in a test (one row per rule), which catches both mistakes in under a second.

**Close everything you didn't explicitly open**

The last rule is `anyRequest().denyAll()`. The common alternative, `anyRequest().authenticated()`, sounds safe but means "any signed-in user may call any endpoint nobody thought about", and anyone can sign up. Rules are checked top to bottom and the first match wins, so specific rules (like the public `/auth/register`) must come before broad ones (like `/api/v1/**`).

**`permitAll()` and `anonymous()` aren't the same**

`permitAll()` lets everyone through. `anonymous()` lets through *only* callers who aren't signed in, so a signed-in caller is refused with 403.

*What happened to us:* the health endpoint was marked `anonymous()`, so a signed-in admin got 403 and could never see the health details meant for admins.

**Protect the health details, not the health endpoint**

Orchestrators like Kubernetes call the health and probe endpoints without credentials. If those endpoints require a login, the orchestrator thinks the app is dead and restarts it, over and over. What should be private is the *detail* (database type, disk usage), which we restrict with `management.endpoint.health.roles: ADMIN`.

*What happened to us:* the first version locked the whole endpoint to admins. A related trap: `show-details: when_authorized` without a role means *any* signed-in user sees the details.

**Errors take a second trip through the filter chain**

When something calls `sendError`, Tomcat makes a second, internal request to `/error`, and Spring Security checks that one too. If `/error` isn't public, every error turns into a bare 401 with an empty body. So `/error` is `permitAll()`.

**Why CSRF protection is off, and when it must come back**

CSRF attacks trick a browser into sending credentials it attaches automatically (cookies, and also Basic credentials it has cached). Our clients are API clients that send credentials deliberately, so CSRF is off, with the reason written in the config. It must come back if credentials ever travel in cookies.

**Some filters are on by default**

The `LogoutFilter` was active even though we never asked for it, and it handles `/logout` *before* the access rules run, so our `denyAll()` didn't apply to it. It's disabled until Phase 2 builds a real logout.

**Declaring your own beans switches Boot's defaults off** *(checked in the source)*

Boot provides a default filter chain and a generated user with a random password. Declaring any `SecurityFilterChain` bean removes the default chain. Declaring a `UserDetailsService`, an `AuthenticationProvider` or an `AuthenticationManager` (and, in Phase 2, a `JwtDecoder`) removes the generated user.

*What happened to us:* once our own user service existed, the test credentials in `application-test.yml` silently stopped working and the integration tests got 401. Tests now create real users with the `TestUsers` helper.

**The order of the filters, and what 401 and 403 mean**

A request passes `DelegatingFilterProxy` (the bridge from Tomcat into Spring) → `FilterChainProxy` (picks the chain and clears the security context afterwards) → the chain's filters: security context, headers, Basic, anonymous, exception translation, authorization. Our `CorrelationIdFilter` runs before all of them, so even rejected requests have a correlation ID.

- **401** means "I don't know who you are"; **403** means "I know who you are, and no".
- A wrong Basic password is rejected by the Basic filter itself and never reaches the authorization step.
- `denyAll()` gives an anonymous caller 401, not 403: it can't forbid someone it hasn't identified.
- A 401 from the filter chain never reaches `@RestControllerAdvice`, because it happens before Spring MVC. That's why those 401s use Boot's default JSON rather than our ProblemDetail format, until Phase 2 adds an `AuthenticationEntryPoint`. The same `AuthenticationException` thrown from a controller *is* handled by the advice.

### 3.2 How a password check works

*Every password check, whether from `/auth/login` or a Basic header, runs through the same pipeline. Knowing its steps is what made lockout and "don't reveal account state" possible.*

**Four parts, four jobs**

The **`AuthenticationManager`** picks a provider that can handle the credentials. The **`DaoAuthenticationProvider`** runs the check. Our **`TaskflowUserDetailsService`** only *finds* the user, and the **`PasswordEncoder`** only *compares* the password with the stored hash.

**Some account checks run before the password check** *(checked in the source, Spring Security 6.5.11)*

The provider runs in this order: load the user → **pre-checks** (by default: locked? disabled?) → compare the password → **post-checks**. If a pre-check fails, the password is still compared (so the timing looks normal), but the result is ignored and the pre-check's error wins.

*Why it mattered:* with Spring's defaults, anyone who knows only an email can learn "this account is unverified" or "this account is locked", without the password. *What happened to us (deliberate failure):* with the default checks, unverified bob with a **wrong** password got a 403 "email not verified".

*The rule we chose:* "locked" stays a pre-check but gets the same 401 as a wrong password. "Unverified" moved to the post-checks, so only someone who typed the right password learns it. "Locked" can't be revealed only after a correct password either: an attacker guessing during a lockout would then recognise the right guess by the different answer.

**There must be exactly one password-checking component, and Spring must build the manager** *(checked in the source)*

Spring builds the application's main `AuthenticationManager` from the single `AuthenticationProvider` bean, if there is one, and gives it an event publisher. Basic authentication uses that manager too. Spring's documentation shows a different pattern, `new ProviderManager(provider)`: that manager has no event publisher (so lockout events never fire), and Basic doesn't use it.

*The rule:* one `DaoAuthenticationProvider` bean carrying our checks, and an `AuthenticationManager` bean that just returns the one Spring built. You'll see a warning at startup ("UserDetailsService beans will not be used…"); it's expected with this setup.

**Every password check announces its result as an event**

After each check, Spring publishes exactly one event, on the same thread, before the request continues. A wrong password and an unknown email both produce `AuthenticationFailureBadCredentialsEvent`; a locked or disabled account produces a different event. The event carries the email exactly as typed, so the listener must normalise it.

**`UserDetails` methods default to "yes"**

Since Spring Security 6.3, `isEnabled()` and `isAccountNonLocked()` have default implementations that return `true`.

*What happened to us:* the principal had `enabled` and `accountNonLocked` fields but didn't override those methods. It compiled, and locked and unverified users would have signed in. Separately, the lock check was written backwards (`!now.isAfter(lockedUntil)`), which would have let users in *during* the lock and locked them forever afterwards.

**`getUsername()` returns the email**

In Spring Security's interface, "username" means "whatever the person signs in with", which for us is the email.

*What happened to us:* the auditor used `authentication.getName()`, which calls `getUsername()`, so `created_by` filled up with email addresses (personal data in every audit column). The auditor now reads `getAppUsername()` from our principal, and returns `"system"` for anything else, including the anonymous token, which claims to be authenticated.

Also: roles need the `ROLE_` prefix, because `hasRole("ADMIN")` looks for an authority named `ROLE_ADMIN`.

**Ask for the password again before sensitive changes, and do it through the same pipeline**

Changing a password requires the current one, even though the user is signed in, because sessions and (in Phase 2) tokens can be stolen. Checking it through the `AuthenticationManager` means a wrong guess counts toward lockout like any other.

*What happened to us (caught in review):* the first version passed the stored hash, `principal.getPassword()`, as if it were the typed password. That never matches, so **every** password change failed, and each failure counted: after five tries the user was locked out of their own account.

### 3.3 Passwords

**bcrypt, and the `{bcrypt}` prefix**

bcrypt is deliberately slow (to make guessing expensive) and salted (the same password produces a different hash each time; the salt is stored inside the hash). We store hashes as `{bcrypt}$2a$10$…`. The prefix tells `DelegatingPasswordEncoder` which algorithm made the hash, so we could move to a new algorithm later without resetting everyone's password: old hashes keep working, new ones use the new default.

**72 bytes, not 72 characters** *(tested on a copy, against two library versions)*

bcrypt only uses the first 72 **bytes** of a password, and an emoji is 4 bytes (but counts as 2 for Java's `length()`, which `@Size` uses).

| Password | Characters (`length()`) | Bytes | Our version (6.5.11) | An older version (6.3.1) |
|---|---|---|---|---|
| 72 × `a` | 72 | 72 | accepted | accepted |
| 73 × `a` | 73 | 73 | `encode()` throws | accepted, silently truncated |
| 19 emoji | 38 | 76 | `encode()` throws | accepted, silently truncated |

On the older version, a *different* password sharing the first 72 bytes logged in. On ours, an over-long password crashed registration with a 500 *(what happened to us, as a deliberate failure)*. The `@MaxUtf8Bytes(72)` check turns that into a clean 400. Login needs the cap too: `matches()` doesn't throw, it just compares the first 72 bytes.

**Writing a custom validation rule**

A custom rule is an annotation plus a validator class. By convention it treats `null` as valid (checking presence is `@NotBlank`'s job). Its messages go in **`ValidationMessages.properties`**, plural. *What happened to us:* the file was first named in the singular, so users would have seen the raw `{…}` key instead of a message.

You can also combine existing rules into one annotation (`@ValidPassword` = not blank + at least 12 characters + at most 72 bytes) *(tested on a copy)*. Each part still reports its own message, and each part must allow being used on another annotation (`ElementType.ANNOTATION_TYPE`), which `@MaxUtf8Bytes` didn't until §1.6.

**Normalise once, at the top, and use those values everywhere**

*What happened to us (caught in review):* registration checked for duplicates using the email as typed, then saved the lowercased version. So `Alice@Example.com` passed the check, and only the database constraint caught the duplicate. When both email and username were taken, whichever constraint fired first decided the error code. Normalise first, then check and save the same values.

**Records print everything in `toString()`**

A Java record's generated `toString()` includes every field. *What happened to us (deliberate failure):* one `log.info("{}", request)` put a plaintext password in the log. Every request record that holds a password, token or email overrides `toString()` to hide it.

### 3.4 Links in emails (tokens)

**What makes a link token safe**

Six properties, each blocking a different attack: **unguessable** (32 random bytes from `SecureRandom`, never a UUID or `java.util.Random`), **stored only as a hash** (a leaked database holds no working links), **expiring**, **single-use**, **tied to one purpose** (a verification link can't reset a password), and **cancelled when a new one is issued**. Plus: never in a URL that reaches a server, never in a log outside dev.

**Why tokens use SHA-256 but passwords use bcrypt**

Slow hashing protects *guessable* secrets like human passwords. A 256-bit random token can't be guessed at any speed, so a fast hash is enough. And we must be able to *look up* the row by the hash, which only works with a deterministic hash; bcrypt's random salt makes lookup impossible.

**Making a link work only once, even with two clicks at the same moment**

"Find the token, check it's unused, mark it used" lets two simultaneous requests both pass the check. Instead, one SQL statement does it all: `UPDATE … SET consumed_at = now WHERE hash = ? AND consumed_at IS NULL AND …`. Postgres runs it atomically, so exactly one request gets "1 row updated" and the other gets 0.

**Why the token goes after the `#` in the link**

Browsers never send the part after `#` to a server, so the token can't end up in access logs, proxy logs or the `Referer` header. The frontend page reads it and sends it in a POST. Mail scanners that pre-open links can't use it up either, because using it requires a POST.

**One live link at a time, guaranteed by the database**

Issuing a new link marks the old one as revoked, and a partial unique index allows only one active link per user and purpose. *What happened to us:* this was your improvement in §1.4. My brief said "delete the old tokens"; revoking plus the index is stronger, because the database itself enforces it. That's where the "recommend the best approach" rule came from.

**A link should live as long as the damage it can do is acceptable**

A verification link's worst case is a wrongly verified address, so 24 hours is fine. A reset link's worst case is a stolen account, so 30 minutes: enough for slow email, short enough that an old link in a forwarded email or a backup is useless.

### 3.5 Transactions and persistence

*Most of Phase 1's silent bugs came from not knowing **when** the database actually does something. This theme is about that timing.*

**Saving isn't the same as committing**

When you call `repository.save(user)`, nothing reaches the database yet; Hibernate just remembers the object. The SQL `INSERT` is sent later, at the **flush**, which normally happens when your `@Transactional` method returns. Even after the flush, the row isn't permanent: other connections can't see it, and it can still be rolled back. Only the **commit** makes it real.

*Why it mattered:* in registration, two people could sign up with the same email at the same moment. The unique constraint stops the second, but with `save()` that error appears *after* the method has returned, outside the `try` that was supposed to turn it into a clean 409. `saveAndFlush()` sends the `INSERT` immediately, inside the `try`, so the error can be caught and translated.

*The rule:* `saveAndFlush()` when you need a constraint error to surface where you can handle it, and never assume "flushed" means "saved".

**Once a database error happens inside a transaction, the transaction is finished**

If a database call inside a `@Transactional` method fails, Spring marks the whole transaction "rollback only". Catching the exception doesn't undo that: if you catch it and carry on, the method seems to succeed, then the commit fails anyway with `UnexpectedRollbackException`, a 500.

*What happened to us:* in resend, two clicks at once could both try to create a verification link, and the second hit the "one active link" constraint. The first version caught that error *inside* the transaction and returned normally, which would have been a 500.

*The rule:* inside a transaction, catch a database error only to turn it into a different error and throw that. To carry on as if nothing happened, catch it *outside* the transaction, after it has rolled back. That's why `RegistrationWorkflow` and `PasswordWorkflow` catch it, not the services.

**Send emails only after the transaction has committed, and from a different class**

An email can't be unsent. If it's sent inside the transaction and the commit then fails, the user gets a link to an account that doesn't exist (a "phantom email"). It also keeps the database transaction open for as long as the mail server takes.

*What happened to us (caught in review):* the first §1.4 version sent the verification email inside the registration transaction.

*The rule:* a class with no transaction (`RegistrationWorkflow`, `PasswordWorkflow`) calls the transactional service, which commits when it returns, and then sends. It has to be a *separate* class: when a method calls another method in the same class, Spring's `@Transactional` is skipped entirely. *What happened to us:* a `MANDATORY` method called from inside its own class did nothing at all.

**Choosing how a method joins a transaction**

- `REQUIRED` (the default) joins the caller's transaction, or starts one.
- `MANDATORY` refuses to run without the caller's transaction. We use it for issuing and consuming tokens, so a token can never be committed separately from the registration or verification it belongs to.
- `REQUIRES_NEW` pauses the caller's transaction and runs in a fresh one that commits on its own. We use it for the lockout counter.

*What happened to us (deliberate failure):* with the counter written in the login's own transaction, the failed login rolled everything back, including the count. **The counter stayed at 0 and nothing ever locked**, with no error anywhere. `REQUIRES_NEW` fixes it, at a price: it needs a second database connection while the first is paused, which is why the login flow itself has no transaction.

**A record should be saved together with what it records**

A failed attempt (the counter, a "login failed" event) must survive the failure, so it's saved in its own transaction. A state change (an account locked, an email verified, a password reset) must be saved in the *same* transaction as the change, so the event exists if, and only if, the change happened.

**Hibernate writes back the whole row, including columns you didn't touch** *(tested on a copy)*

When you change one field on a loaded entity, Hibernate's `UPDATE` writes **every column** by default, using the values it loaded at the start. Anything another statement changed in between is overwritten with the old value.

*What happened to us:* a password reset needed to unlock the account and set the new password. Unlocking with the lockout's SQL query, then setting the password on the entity, **put the lock back**: the entity still remembered "locked" and wrote it back. The obvious fix, `clearAutomatically = true` on the query, made Hibernate forget the entity instead, so **the new password was never saved**. Neither produced an error. The same problem means any "load, then save later" flow could erase a lock applied in between by a concurrent wrong-password guess.

*The rule:* `@DynamicUpdate` on `UserAccount`, so Hibernate writes only the columns that actually changed, and account changes go through entity methods (`resetPassword`, `changePassword`) instead of mixing SQL queries and entity changes in one transaction.

**SQL update queries skip Hibernate's bookkeeping**

A `@Modifying` query goes straight to the database: entities already loaded in the same transaction keep their old values, and automatic audit columns (`updated_at`) don't change. That's acceptable for the lockout counter; just know it.

**A method name can't choose which column a query returns**

*What happened to us (tested on a copy):* `Optional<Long> findIdByEmail(String email)` looked like it would return just the id. In Spring Data's naming rules, the words between `find` and `By` are ignored, so it loaded the whole `UserAccount`, then failed with `JpaSystemException` because it couldn't turn an account into a `Long`. It only failed for emails that *exist*, so a quick test with an unknown email looked fine. The fix is to write the query: `@Query("select u.id from UserAccount u where u.email = :email")`.

**Smaller persistence lessons**

- **Database defaults don't apply to Hibernate's inserts.** `DEFAULT 'USER'` only helps when an `INSERT` leaves the column out, and Hibernate sends every column, unset ones as `NULL`. Set defaults in Java.
- **A record's compact constructor runs before its fields are assigned.** *What happened to us:* a properties record checked `emailVerificationTtl()` (the accessor) inside its constructor, which returned `null` and crashed every startup. Use the parameter.
- **Configuration properties bind through the constructor,** which is why they're records. Plain classes with private fields and no setters bind nothing.
- **`@Profile("dev")` only works on a bean**, so the class also needs `@Component`.
- **JPA's `@Table` takes `name = …`.** *What happened to us:* `@Table("security_events")` didn't compile.
- **`ddl-auto: validate` doesn't check primary keys.** *What happened to us:* `user_tokens` was first written without one, and nothing would have complained.
- **A migration that has run can never be edited.** Flyway stores a checksum of each one and refuses to start if it changes, even by a comment. V1–V4 are frozen; changes go in a new file.

### 3.6 Not revealing who has an account, and lockout

**Which endpoints may reveal that an email is registered**

Any difference in status, body or timing between "this email has an account" and "it doesn't" lets someone test a list of emails against the system. Registration reveals it on purpose (decision above). Login gives the same 401 and the same body for unknown, wrong and locked. Resend and reset requests always answer 202.

**Timing still leaks a little**

For a known email, resend and reset do database work and send an email; for an unknown one they return immediately. Login is closer, because both paths pay for one bcrypt, but a known email also triggers a few database writes. Phase 9's asynchronous email sending removes most of the gap; the login gap hasn't been measured.

**How the lockout counter stays correct under pressure**

The counter has to be right exactly when it's under attack: many wrong guesses arriving at once.

- **The increment happens in SQL** (`SET failed_login_attempts = failed_login_attempts + 1`), so two simultaneous failures can't both read 3 and write 4.
- **A second statement applies the lock** only where the count has reached 5, and resets the count in the same statement. Its "rows updated" count tells exactly one request that *it* applied the lock, so `ACCOUNT_LOCKED` is recorded once.
- **It fails loudly.** The listener runs inside the password check, so if the counter can't be written, the request fails with a 500 rather than letting an uncounted guess through.

*What happened to us (caught in review):* the broken `findIdByEmail` (above) threw for every real account, and the first listener caught database errors and only logged them. Every wrong password still got a normal 401, but **the counter never moved and nothing ever locked**. Only an ERROR line in the log showed it.

**Lockout can be used against the user**

Anyone who knows your email can lock you out by guessing badly on purpose. The automatic unlock after 15 minutes limits the damage; the real fix is rate limiting by IP address (Phase 10), which slows the attacker instead of punishing the account owner.

### 3.7 The database as the last line of defence

*The application validates input, but the database has the final say: every path that writes data, including a hand-typed SQL fix, has to pass its rules.*

- **`CHECK` constraints catch what code forgets:** emails stored lowercase, usernames without `@`, password hashes with their `{id}` prefix, valid role names, token hashes that are 64 hex characters (so a raw token can't be stored by mistake), and only sensible combinations of event type and failure reason.
- **Every constraint is named,** because the application identifies errors by constraint name. Postgres silently cuts names longer than 63 characters, which would break that matching.
- **`password_hash` is `varchar(200)`,** sized for the longest algorithm we might move to, not just bcrypt's 68 characters.
- **IP addresses use Postgres's `inet` type.** It rejects anything that isn't an address and stores one canonical form, so `0:0:0:0:0:0:0:1` and `::1` compare equal *(checked on the dev database)*. Hibernate maps Java's `InetAddress` to it without any annotation.
- **The security log is protected twice.** `@Immutable` tells Hibernate never to update an event, but it ignores changes silently. A database trigger rejects any `UPDATE` loudly, whoever sends it.
- **Every foreign key gets an index and an `ON DELETE` rule.** Postgres doesn't index foreign keys automatically, and without a rule, deleting a user would fail because their tokens still point at them.
- **Shared code never imports a feature.** `common` holds things every feature uses; if it imports from `user`, the layering breaks. *What happened to us:* it happened twice (`TokenProperties` importing `TokenPurpose`, and the security config importing `TaskflowUserDetailsService`).

### 3.8 Logs, personal data and the client's IP

**Log ids, never emails, passwords or tokens**

Logs get copied, shipped and kept for a long time, so they must not hold personal data or credentials. Account-related log lines carry the account id only. The one deliberate exception is the dev-only email sender, which logs each email so you can copy the link. Note: the dev profile also logs SQL parameter values at `TRACE` level, which includes emails and hashes; that's dev-only and below the normal `INFO` level.

**The client's IP is the network connection's address, not a header**

`request.getRemoteAddr()` is the address of whoever opened the connection, which the client can't fake. `X-Forwarded-For` is just a header: anyone can send any value. Behind a real proxy, forwarded headers should be trusted only from that proxy (Phase 11). Be aware that Boot turns forwarded-header handling on by itself when it detects a cloud platform like Kubernetes *(checked in the source)*. Also: `InetAddress.getByName(null)` returns the loopback address, so a missing IP must stay `null` rather than be recorded as `127.0.0.1`.

**Names in a JSON response are a contract**

Once a client depends on a field name, renaming it is a breaking change. *What happened to us:* a misspelt `occuredAt`, and `password` in one request vs `newPassword` in another, were both caught before any client existed.

### 3.9 Testing security

**A web slice can test the access rules, but not everything** *(checked on this project)*

A `@WebMvcTest` that loads only the security config and no controllers is a neat way to test rules: a request that's allowed through finds no controller and gets 404; a blocked one gets 401 or 403. But a slice silently can't test two things:
- **Actuator rules:** the slice has no actuator, so those rules match nothing and requests fall to `denyAll()` (401/403 in the slice, 200 in the real app).
- **Real usernames and passwords:** the slice runs as the `dev` profile, where test users don't exist.

Those belong in the integration test. A slice also never loads services, so if the security config needs one, the test must provide a mock, and the config must ask for the *interface* (`UserDetailsService`), or a mock won't fit.

**Two ways to be signed in during a test**

`with(user("u"))` puts a ready-made signed-in user in place and skips the password check: it tests *authorization* only. `httpBasic("u", "p")` sends real credentials through the real check.

**Test as the weakest user that should succeed**

*What happened to us:* the first feature tests ran as an admin. If a rule wrongly required admin, every normal user would be locked out and those tests would still pass.

**Test configuration belongs in `src/test`**

*What happened to us:* `application-test.yml`, with an admin test user, was in `src/main/resources`, which means it was packaged into the production jar: one wrong profile setting away from a known admin login in production.

**The same mistake can show different status codes in different tests**

Adding only the security starter broke 6 of 13 tests. The slice tests saw 401 for GET and 403 for POST (CSRF). The integration test saw 401 or **302** for the same POST, because real Tomcat makes the second internal trip to `/error` and the default chain answered with a redirect to a login page. MockMvc never makes that second trip.

**Patterns we used**

- Build a time-dependent service by hand with a fixed `Clock` (not a mocked one), and test the exact boundary instant.
- Clear `SecurityContextHolder` after any test that sets it; it's thread-local and would leak into the next test.
- Prove an enum is stored as text with a native SQL query; through JPA both forms look the same.
- Hash the shared test password once; bcrypt is slow on purpose.

**A test only counts if it can fail**

For each test, plant the bug it's meant to catch and confirm it turns red. All 15 planted bugs for §1.1–§1.2 were caught (section 7).

### 3.10 Tooling

- **IntelliJ's "Delegate build/run actions to Maven" runs the tests before starting the app,** so one failing test means no app. Use `./mvnw spring-boot:run`, or *Runner → Skip Tests* for IDE runs.
- **`./mvnw spring-boot:run --debug` turns on Maven's debug output, not the app's.** The right form is `-Dspring-boot.run.arguments=--debug`.
- **`spring-boot:run` doesn't reload code.** *What happened to us:* deliberate failure 1 seemed not to work, because the app hadn't been restarted.
- **Creating a user by hand in `psql`:** paste into an interactive session (`psql -c "…"` lets the shell mangle the `$` signs in a bcrypt hash), and take the id from `nextval('global_id_seq')`.
- **After a deliberate failure, check the diff for leftovers.** *What happened to us:* a temporary `@Size(max = 72)` and a log line printing the request were still in the code at wrap-up.
- **Stage new files when committing.** *What happened to us:* a pushed commit referenced a test class that hadn't been added, so a fresh clone didn't compile.

### 3.11 Other things caught in review

Each of these would have shipped without a review:

- `/auth/login` had no exception handling at first, so every wrong password was a 500 with a stack trace in the log.
- The login response was built from the principal, so `displayName` and `createdAt` were `null`, `emailVerified` came from an unrelated flag, and an unexpected principal type gave an empty 200.
- `UserAccountResponse` hard-coded `emailVerified: false`, which would have been wrong for every verified user as soon as it was reused.
- Unknown errors were wrapped in a plain `RuntimeException` (hiding their real type), `.get()` was called on lookups, and the password change silently did nothing if the account was missing (while still answering 204 and emailing "your password was changed").
- Every security-event factory accepted a failure reason, so recording a lockout with one would have broken a database rule (a 500 at the exact moment an account locks).
- The login-history query took one event type instead of three, hard-coded its sort order in the method name, and would have inherited `createdAt` as a default sort from `PageableFactory` (a field `SecurityEvent` doesn't have, so a 500). An extra `PageableFactory` method also skipped the tiebreaker that keeps pages from repeating rows.
- An entity read the system clock directly (`Instant.now()`) instead of taking the time from the service; a class named `…Response` held a raw token; a result object carried a `null` token; and `.with("field", …)` was called twice on a map, so the second value replaced the first.
- A create returned 200 instead of 201; `@Size(min = 3)` on display names would have rejected real names like "Li" or "王"; a 409 didn't say which field clashed; a failure log printed an email; and the verification link was hard-coded to `localhost:3000`.
- Resend didn't normalise the email, so `Alice@Example.com` silently got no email; token lifetimes were defined in two places; and a `CHECK` compared two different clocks (`expires_at > created_at`).
- The principal's flags were hard-coded to `true` with no authorities, there was no way to create a `UserAccount`, and an IDE auto-imported `java.awt.*`.
- **My own mistake:** my early §1.5 notes said to record `ACCOUNT_LOCKED` from `/auth/login` only, which would have missed every lockout caused through Basic.

---

## 4. Production practices, and what each prevents

| Practice | What it prevents |
|---|---|
| Deny everything not declared; public paths listed exactly and only for POST; specific rules before general ones | New endpoints silently open to every signed-in user; public rules accidentally opening other methods |
| No sessions; CSRF off with the reason written down; logout off until it's built; default security headers kept | Session theft and fixation; forgetting CSRF when cookies arrive; an unguarded `/logout` |
| Health public, its details admin-only; actuator rules by endpoint name | Restart loops from failing probes; leaking infrastructure details; rules breaking when paths move |
| bcrypt with the `{bcrypt}` prefix and a `CHECK` on it; one password rule in `@ValidPassword` | A forced reset for everyone when changing algorithm; unusable hashes; rules drifting between endpoints; truncated passwords |
| Normalise emails and usernames once (`Locale.ROOT`), with lowercase `CHECK`s | Duplicate accounts that differ only in case; the Turkish-locale `I → ı` bug |
| A small immutable principal with the id and username; the auditor checks its type | Stale entities in the security context; an extra query per request; emails or `anonymousUser` in audit columns |
| An injected `Clock`, with times passed into entities and queries | Time rules that can only be tested by sleeping; two different "nows" |
| Validation, duplicate checks and token checks before any bcrypt | Garbage requests burning ~100 ms of CPU each |
| Requests holding secrets or emails hide them in `toString()`; logs carry ids only | Passwords, tokens and personal data in logs |
| `saveAndFlush` and translation by constraint name, in the feature package | A race returning a different, generic error that exposes the constraint name |
| Tokens: secure random, stored hashed, used once by one conditional `UPDATE`, one error for every bad token, sent in the URL fragment, one live link per user | Guessable or leaked links; double use; hints about which tokens exist; links consumed by mail scanners |
| Emails sent after commit from a separate class; errors caught to carry on only outside the transaction | Phantom emails; mail delays holding transactions; `UnexpectedRollbackException` |
| "Locked" checked before the password with the generic answer, "unverified" after it; only the three expected errors translated | Revealing account state without the password; a lockout that confirms the right guess; outages reported as "wrong password" |
| The lockout counter driven by events, incremented in SQL, in its own transaction, failing loudly, reset only when needed | Basic as a side door around lockout; lost counts; the rollback trap; lockout silently off; a write on every request |
| An append-only event log (`@Immutable` plus a trigger), `CHECK`s, `inet`, IPs from the connection | Rewritten history; impossible rows; forged IPs |
| Events saved together with what they record | Events for changes that rolled back; failed attempts lost with their failure |
| `@DynamicUpdate` and entity methods for account changes | A save undoing an unlock, or erasing a lock applied in between |
| Reset: always 202, sent to the stored address, 30-minute single link, check the new password before using the link and the link before hashing, everything saved together, owner notified; a password change cancels the reset link | Revealing who has an account; long-lived takeover links; links wasted by a typo; half-finished resets; unnoticed takeovers; an old link undoing a change |
| The user's id taken from the signed-in principal, never from the URL; a stable, indexed sort for history | Reading other users' data by changing an id; pages that skip or repeat rows |
| Tests as the weakest role, access rules as a table, test config in `src/test`, every test proven able to fail | Admin-only bugs hidden; rules that let everyone through; test credentials in production; tests that can't fail |

---

## 5. Interview questions

*Each answer is written the way you'd say it out loud.*

### The filter chain and its configuration

1. **What happens between a request arriving and your controller running?**
   Tomcat hands the request to Spring's `DelegatingFilterProxy`, which passes it to `FilterChainProxy`. That picks the matching security chain and runs its filters in order: it sets up the security context, adds headers, checks Basic credentials, marks anonymous callers, translates security errors into 401/403, and finally checks the access rules. Only then does the request reach the `DispatcherServlet` and my controller.

2. **Why are there three pieces: `DelegatingFilterProxy`, `FilterChainProxy` and `SecurityFilterChain`?**
   Tomcat creates its own filters and knows nothing about Spring beans, so `DelegatingFilterProxy` is the bridge. `FilterChainProxy` is the single entry point that picks a chain and clears the security context afterwards. `SecurityFilterChain`s are the chains I declare; Phase 10 will add a second one for API keys.

3. **What's the difference between 401 and 403, and why does `denyAll()` give an anonymous user 401?**
   401 means "I don't know who you are", 403 means "I know who you are, and you can't". When access is denied to an anonymous caller, Spring asks them to authenticate first, which is a 401: it can't forbid someone it hasn't identified.

4. **Why isn't `anyRequest().authenticated()` "deny by default"?**
   Because it means any signed-in user can call any endpoint nobody declared, and with self-registration anyone can be signed in. I use `denyAll()` last, so a forgotten endpoint is closed.

5. **`permitAll()` vs `anonymous()`?**
   `permitAll()` lets everyone in. `anonymous()` lets in only callers who aren't signed in, so a signed-in caller gets 403. I hit that on the health endpoint: an admin couldn't see the health details.

6. **When is it safe to disable CSRF?**
   When the browser doesn't attach credentials automatically. Bearer tokens in a header are safe; cookies aren't. Basic is in between, because browsers cache and resend Basic credentials, so it's acceptable only for API clients. I wrote the reason next to the setting so nobody forgets to turn CSRF back on if cookies appear.

7. **Why can't your `@RestControllerAdvice` handle the 401 from a wrong Basic password?**
   That 401 happens inside the filter chain, before Spring MVC runs, so the advice never sees it. The same exception thrown from a controller is handled. Phase 2 adds an `AuthenticationEntryPoint` to give the filter-chain 401 the same format.

8. **Your security config started fine, but every request returned 500. How?**
   Spring checks URL patterns only when a request reaches them. I'd written the public paths as `{register, login, …}`, which Spring reads as a path variable, and an actuator endpoint as a path instead of its name. Both failed on the first real request. Now every rule is a row in a test.

9. **How did you secure the health endpoint, and what happens if the liveness probe needs a login?**
   The endpoint is public, because orchestrators call it anonymously, and only the details are restricted to admins. If the probe needed a login, the orchestrator would see failures, think the app was dead, and restart it in a loop.

10. **How does Boot's default security get out of the way when you configure your own?**
    Two independent conditions: declaring any `SecurityFilterChain` removes the default chain, and declaring a user service, an authentication provider or a manager removes the generated user and password.

11. **You set the session policy to `STATELESS`. Is that enough to guarantee no sessions?**
    No, it only stops Spring Security creating one; other code still could. So I check it: no response carries a `Set-Cookie`.

### The password check and the signed-in user

12. **What do the `AuthenticationManager`, `AuthenticationProvider`, `UserDetailsService` and `PasswordEncoder` each do?**
    The manager picks a provider that can handle the credentials. The provider runs the check. The user service only finds the user, and the encoder only compares the password with the stored hash.

13. **Why doesn't your entity implement `UserDetails`?**
    The signed-in user lives in the security context for the whole request, outside any database transaction. An entity there would be a stale, detached copy with lazy fields waiting to throw. So I use a small immutable principal with the id, username and a few flags.

14. **What's the difference between `hasRole` and `hasAuthority`?**
    `hasRole("ADMIN")` adds the `ROLE_` prefix and looks for `ROLE_ADMIN`; `hasAuthority` looks for the exact string. If the prefix isn't added when authorities are built, a real admin gets 403.

15. **Your `created_by` column filled up with email addresses. Why?**
    The auditor used `authentication.getName()`, which returns `getUsername()`, and in Spring Security that's whatever the user signs in with: the email. It now reads the app username from our principal type, and returns "system" for anything else.

16. **How do you test code that depends on the current time?**
    I inject a `Clock`. Production uses the system clock; tests use a fixed one, and I test the exact boundary instant, like "unlocked exactly at `locked_until`".

17. **Why `Locale.ROOT` when lowercasing emails?**
    Without it, Java uses the server's language rules. Under Turkish, a capital `I` becomes a dotless `ı`, which silently creates a different email.

18. **Your column has `DEFAULT 'USER'`, but the insert failed on NOT NULL. Why?**
    Hibernate sends every mapped column in its `INSERT`, unset ones as `NULL`. A database default only applies when the column is left out, so defaults have to be set in Java.

19. **How is the first admin created?**
    By hand, with an `UPDATE` on an existing account. Never as a seed user in a migration, because that would ship a known admin password to production.

20. **Where does your `AuthenticationManager` come from, and how do Basic and `/login` share it?**
    I declare one `DaoAuthenticationProvider` bean with my custom checks. Spring builds the main manager from it, with an event publisher, and Basic authentication uses that manager; my login service injects the same one. If I'd created my own `ProviderManager`, it would have no event publisher, and Basic wouldn't use it.

### Passwords

21. **How is a password stored, and why does the same password hash differently each time?**
    With bcrypt, which is deliberately slow and adds a random salt to each hash, stored inside the hash itself. To check a password, it reads the salt from the stored hash and hashes the attempt the same way.

22. **What's the `{bcrypt}` prefix for, and how would you move everyone to a new algorithm?**
    It tells `DelegatingPasswordEncoder` which algorithm made the hash. To migrate, I'd change the default for new hashes and re-hash each user's password the next time they sign in successfully; old hashes keep working meanwhile.

23. **Why can't you just use `@Size(max = 72)` for a bcrypt password?**
    `@Size` counts Java characters, but bcrypt's limit is 72 bytes, and an emoji is 2 characters but 4 bytes. On our library version an over-long password makes the encoder throw, which is a 500; on an older version I tested, everything after byte 72 was ignored, so a different password could sign in.

24. **How could a password end up in your logs?**
    A Java record's generated `toString()` prints every field, so one `log.info("{}", request)` does it. I saw it happen. Every request record holding a secret overrides `toString()`.

### Registration and links in emails

25. **Does your app reveal which emails have accounts?**
    Registration does, on purpose: a clear "already registered" helps real users more than it helps attackers, and rate limiting will slow down probing. Login, resend and reset don't reveal it in their status or body. Resend and reset still leak a little through timing, since a known email does more work; asynchronous sending in Phase 9 removes most of that.

26. **Two people register the same email at the same instant. What happens?**
    Both pass the "already exists?" check, but the database's unique constraint rejects the second insert. Because I use `saveAndFlush`, that error appears inside my `try` and becomes the same 409 as the normal case. *(Reasoned from the code; the race wasn't run.)*

27. **Why does registration return 201 without a `Location` header?**
    `Location` should point to where the new resource can be read, and there's no endpoint where a user can read another user. Advertising one would invite someone to build it.

28. **Why normalise the email before checking for duplicates, not just before saving?**
    Otherwise the check misses `Alice@x.com` vs `alice@x.com`, the database constraint does the work instead, and when both email and username clash, the wrong error code can win.

29. **How would you design a password-reset or verification token?**
    32 random bytes from `SecureRandom`; store only its SHA-256 hash; give it an expiry, a single use, and a purpose; cancel the old one when issuing a new one; carry it in the URL fragment and send it back in a POST; never log it.

30. **Why SHA-256 for tokens but bcrypt for passwords?**
    Passwords are guessable, so they need a slow hash. A 256-bit random token can't be guessed at any speed, and I need a deterministic hash so the database can find the row by it.

31. **How do you make sure a token can only be used once, even with simultaneous requests?**
    One conditional `UPDATE` that sets "used" only where it isn't used, expired or revoked yet. Postgres runs it atomically, so exactly one request updates a row and the others update none.

32. **What happens if the mail server is down when someone registers?**
    The account is already saved; the failed send is logged with the account id; the response is still 201; and the user can ask for a new link.

### Transactions and persistence

33. **Why not send the email inside the transaction?**
    If the commit fails afterwards, the user has a link to something that doesn't exist. And the transaction, with its database connection, stays open for as long as the mail server takes.

34. **What's the difference between flush and commit?**
    Flush sends the SQL to the database inside the open transaction: nobody else can see it yet, and it can still be rolled back. Commit makes it permanent and visible. "I flushed, so it's saved" is wrong.

35. **You caught the exception, so why did the transaction still fail?**
    When a database call inside the transaction failed, Spring marked the whole transaction "rollback only". Catching the exception doesn't clear that, so the commit fails with `UnexpectedRollbackException`. To carry on, catch it outside the transaction.

36. **When would you use `MANDATORY` and `REQUIRES_NEW`?**
    `MANDATORY` for building blocks that must be part of a bigger transaction, like issuing a token during registration. `REQUIRES_NEW` for records that must survive the caller's rollback, like a failed-login counter. It costs a second database connection while the first is paused.

37. **A password reset unlocked the account, and then the lock came back. How?**
    The unlock was a direct SQL update, but the account entity had been loaded before it, and Hibernate's update at commit wrote back every column, including the old lock. `@DynamicUpdate` makes Hibernate write only the columns that changed. The other obvious fix, `clearAutomatically`, made the password change disappear instead.

38. **Why did a record's constructor throw a `NullPointerException` on every startup?**
    It called the accessor inside the compact constructor, and fields aren't assigned until that constructor body finishes. Use the parameter instead.

39. **Can you edit a migration that has already run?**
    No. Flyway stores a checksum of each applied migration and refuses to start if it changes. You fix forward with a new version.

### Login, lockout and the security log

40. **How does your login avoid revealing whether an account exists?**
    An unknown email, a wrong password and a locked account all get the same 401 with the same body, and all cost one bcrypt check, so timing is similar. Only someone who typed the correct password learns that the email isn't verified. A known email still does a few extra writes; I haven't measured that gap.

41. **Why doesn't a locked account get a "locked" message?**
    If "locked" were shown only when the password was right, an attacker guessing during a lockout would recognise the correct guess by the different answer. So "locked" is checked before the password and gets the generic 401.

42. **How does your lockout counter survive a failed login rolling back?**
    It isn't part of the login's transaction. A listener for Spring's authentication events writes the counter in its own `REQUIRES_NEW` transaction. I tried the alternative on purpose: with the counter in the login's transaction, it stayed at zero.

43. **How does lockout behave under many simultaneous wrong passwords?**
    The increment happens in SQL, so no updates are lost. A second, conditional update applies the lock only once the count reaches five, and its row count tells exactly one request that it applied the lock.

44. **Why is the counter driven by events, but login history recorded by the login endpoint?**
    Every password check must count, including Basic. But Basic authenticates every single request, so recording "logins" from the events would log every API call as a login.

45. **Your lockout passed a manual test but never locked anyone. How?**
    A query that only failed for real accounts, plus a listener that caught database errors and just logged them. Every response was still a normal 401. Now the listener fails loudly instead.

46. **Isn't lockout a denial-of-service risk?**
    Yes, anyone who knows your email can lock you out. The automatic unlock limits it; the real fix is per-IP rate limiting in Phase 10, which slows the attacker instead of the victim.

47. **How do you get the client's IP address?**
    From the network connection, `getRemoteAddr()`. `X-Forwarded-For` is a header anyone can set. Behind a proxy, I'd trust forwarded headers only from that proxy, and I know Boot enables that automatically on cloud platforms.

48. **How do you make an audit table impossible to edit?**
    In the application, no setters and `@Immutable`. In the database, a trigger that rejects every `UPDATE`, because `@Immutable` ignores changes silently instead of failing.

### Password reset and change

49. **How would you design a password-reset flow?**
    The request always answers 202 and sends a link only to the address on file. The link is single-use, hashed at rest, lasts 30 minutes, and only one is live at a time. On confirm: validate the new password first, then use up the link, then hash; save the change, the unlock and the event together; email the owner afterwards; and don't sign them in automatically.

50. **Why 30 minutes for a reset link but 24 hours for verification?**
    The lifetime should match the damage a stolen link can do. A reset link can take over the account; a verification link can only verify an address.

51. **What happens to an outstanding reset link when the user changes their password?**
    It's cancelled in the same transaction as the change. Otherwise whoever requested it could use it to take the account back.

52. **Why does your change-password endpoint check the current password through the `AuthenticationManager`?**
    So a wrong guess counts toward lockout. A plain `passwordEncoder.matches()` would let someone with a stolen session guess the current password as often as they like.

53. **Your change-password endpoint rejected every correct password and locked users out. Why?**
    It passed the stored hash as if it were the typed password. That never matches, so every attempt failed, and each failure counted toward lockout.

54. **After a password change, what happens to the user's other signed-in devices?**
    Today, nothing, and I know that. In Phase 2, every token issued before `password_changed_at` will be rejected; that's why the column exists already.

### Testing

55. **How do you test security rules, and why as a plain user rather than an admin?**
    Each rule gets a pair of tests: a caller who should get through and the nearest caller who shouldn't. The rules live in a table-driven test, URL rules in a fast slice test, actuator rules and real passwords in an integration test, and I plant each bug once to prove its test can fail. Testing as the weakest role that should succeed catches rules that wrongly require admin.

**Can't answer yet:** how big the login timing gap is (not measured) · how many updates a Java-side counter loses under 20 simultaneous guesses (that deliberate failure wasn't run) · the measured outcome of the registration race with `save` vs `saveAndFlush` (not run).

---

## 6. Commands

```bash
./mvnw spring-boot:run                       # run the app (compiles the tests but doesn't run them)
./mvnw spring-boot:run -Dspring-boot.run.arguments=--debug | tee target/boot-debug.log   # Boot's auto-configuration report
./mvnw clean test                            # the full suite (Docker must be running)
```

**Create an account, verify it and sign in (dev).** The verification and reset links appear in the application log, after `#token=`.

```bash
curl -s -i -H 'Content-Type: application/json' -d '{"email":"carol@example.com","username":"carol","displayName":"Carol","password":"carol-password-1"}' localhost:8080/api/v1/auth/register
curl -s -i -H 'Content-Type: application/json' -d '{"token":"<TOKEN>"}' localhost:8080/api/v1/auth/verify-email
curl -s -i -H 'Content-Type: application/json' -d '{"email":"carol@example.com","password":"carol-password-1"}' localhost:8080/api/v1/auth/login
curl -s -i -u carol@example.com:carol-password-1 localhost:8080/api/v1/organizations        # Basic; wrong passwords here count toward lockout too
curl -s -u carol@example.com:<password> 'localhost:8080/api/v1/users/me/login-history?size=5'
```

**Reset and change a password.**

```bash
curl -s -i -H 'Content-Type: application/json' -d '{"email":"carol@example.com"}' localhost:8080/api/v1/auth/password-reset/request
curl -s -i -H 'Content-Type: application/json' -d '{"token":"<TOKEN>","newPassword":"a-new-password-1"}' localhost:8080/api/v1/auth/password-reset/confirm
curl -s -i -u carol@example.com:<password> -X PUT -H 'Content-Type: application/json' -d '{"currentPassword":"<password>","newPassword":"another-password-2"}' localhost:8080/api/v1/users/me/password
```

**Race an endpoint:** send 20 requests at once and count the status codes. Swap in any endpoint and body.

```bash
seq 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -d '{"token":"<TOKEN>","newPassword":"pw-number-{}-long"}' localhost:8080/api/v1/auth/password-reset/confirm | sort | uniq -c
```

**A 76-byte password** (19 emoji), for testing the 72-byte limit:

```bash
P=$(python3 -c 'print("\U0001F600"*19)'); printf '%s' "$P" | wc -c
```

**Useful SQL in the dev database:**

```sql
update user_accounts set role = 'ADMIN' where username = 'alice';                                   -- make the first admin
update user_accounts set failed_login_attempts = 0, locked_until = null where username = 'carol';   -- unlock an account
select purpose, consumed_at is not null as used, revoked_at is not null as revoked, expires_at
from user_tokens order by id desc limit 10;                                                         -- recent links
select event_type, failure_reason, host(ip_address) as ip, occurred_at
from security_events order by id desc limit 15;                                                      -- recent security events
```

**Adding a user by hand:** create a hash with `htpasswd -bnBC 10 "" '<password>' | tr -d ':\n'`, put `{bcrypt}` in front of it, and paste the `INSERT` into an **interactive** `psql` session (not `psql -c`, where the shell mangles the `$` signs). Use `nextval('global_id_seq')` for the id.

**Reading framework source instead of guessing:**

```bash
unzip -p ~/.m2/repository/org/springframework/boot/spring-boot-autoconfigure/3.5.16/spring-boot-autoconfigure-3.5.16-sources.jar \
  org/springframework/boot/autoconfigure/security/servlet/UserDetailsServiceAutoConfiguration.java | grep -A3 ConditionalOnMissingBean
```

**Logging switches for investigating** (dev only; turn them off again): `org.springframework.security.web.DefaultSecurityFilterChain: DEBUG` prints the filter list at startup · `org.springframework.security: TRACE` follows one request through every filter · `org.springframework.orm.jpa.JpaTransactionManager: DEBUG` shows exactly when a transaction commits.

---

## 7. Deliberate failures and mutation checks

### Failures we actually saw

| What we broke | What we saw | What it taught |
|---|---|---|
| Added only the security starter, no configuration | 6 of 13 tests failed: the slice saw 401 and 403, the integration test 401 and a 302 redirect; a generated password appeared in the log | Boot's default chain, and how the same cause shows differently in different kinds of test |
| Wrote the public paths with `{…}`, and an actuator endpoint as a path | Every POST, then every GET, returned 500, with a clean startup | Rules are only checked when a request arrives |
| Used `anonymous()` instead of `permitAll()` on health | A signed-in caller got 403 | `anonymous()` excludes signed-in users |
| The auditor used `authentication.getName()` | `created_by = alice@example.com` | `getUsername()` is the email |
| Logged a request record without a masked `toString()` | The plaintext password in the log | Records print every field |
| Used `@Size(max = 72)` and registered with 19 emoji | A 500 from the encoder; with `@MaxUtf8Bytes`, a clean 400 | Characters aren't bytes |
| Spring's default checks; unverified bob signs in with a **wrong** password | 403 "email not verified": the account's state leaks without the password | The order of the checks matters |
| The lockout counter in the login's transaction | After the wrong passwords, the counter was still 0, with no error | `REQUIRES_NEW` and the rollback trap |
| *(Tested on a copy)* `findIdByEmail` as a derived query, with a listener that swallowed database errors | `JpaSystemException` for every real account; lockout would have been silently off | Write the query; fail loudly |
| *(Tested on a copy)* An SQL unlock followed by an entity save; then with `clearAutomatically`; then with `@DynamicUpdate` | The lock came back / the password change was lost / both changes kept | Hibernate writes the whole row |
| *(Caught in review)* The verification email sent inside the registration transaction | Not observed at runtime; fixed before a failed commit could produce a phantom email | Send after commit |
| *(Caught in review)* The stored hash passed as the current password | Would have rejected every password change and locked the user out after five tries | Pass what the user typed |

### Failures planned but not run

These are written up, with commands, in `PHASE_1_REQUIREMENTS.md`:
- **§1.3:** 20 simultaneous registrations with one email, with `save()` vs `saveAndFlush()`.
- **§1.4:** a phantom email on purpose; the transaction skipped by calling a method in the same class; two simultaneous uses of one link with check-then-act; catching the error inside the transaction; starting `prod` without an email sender.
- **§1.5:** Spring's documented manager bean; losing counts with a Java-side counter; showing "locked" only after a correct password; recording login history from the events; trusting `X-Forwarded-For`; an `UPDATE` on `security_events`.
- **§1.6:** the SQL unlock and `clearAutomatically` in the running app; validating the new password after using the link; checking the current password with `matches()`.

### Mutation checks: 15 out of 15 caught

Each bug was planted in a copy of the project, and the suite had to turn red. These cover §1.1–§1.2; later sections have no tests yet.

| Planted bug | Tests that failed |
|---|---|
| Public auth paths changed to `anonymous()` | 1 |
| `denyAll()` changed to `authenticated()` | 3 |
| The POST-only restriction removed | 2 |
| Health and info made admin-only | 6 |
| The admin-only health details setting removed | 1 |
| The `/error` permit removed | 1 |
| CSRF turned back on | 11 |
| `/api/v1/**` made admin-only | 4 |
| Lock check inverted | the lock-boundary unit test and the locked-user integration test |
| Auditor using `getName()` | the auditor unit test and the organization `createdBy` integration test |
| `isEnabled()` override removed | the unverified-user unit and integration tests |
| `ROLE_` prefix dropped | the authority unit test and the admin metrics integration test |
| `Locale.ROOT` dropped | the Turkish-locale normalisation test |
| Email not normalised in the service | the normalisation unit test and the case-insensitive login test |
| `role` stored as a number (`ORDINAL`) | the app refused to start: `ddl-auto: validate` failed every database-backed test context (32 errors) |

---

## 8. Carried debt

### Tests owed

None will be written before Phase 2 (your decision). The planned lists are in each sub-section's *Tests* in `PHASE_1_REQUIREMENTS.md`:
- **§1.3 registration:** validation, masking, normalisation, the duplicate and race paths.
- **§1.4 email verification:** token generation and hashing, single use, expiry, revoke, sending after commit.
- **§1.5 login and lockout:** needs a test clock you can move forward. The rollback trap, the Basic side door, unlocking after 15 minutes, identical 401s, 403 for unverified, history.
- **§1.6 reset and change:** the order of checks, revoking links, `@DynamicUpdate`, the 20-way race.
- **Also owed:** the Phase 0 test proving error bodies never echo submitted values, mutation checks for all of the above, and the races as automated tests.

### Measurements owed

- The login timing gap between a known and an unknown email.
- bcrypt cost 10 vs 12.
- The registration race.

### Scheduled for later phases

- **Phase 2:**
  - make the filter chain's 401/403 use the ProblemDetail format (an `AuthenticationEntryPoint` and `AccessDeniedHandler`);
  - reject tokens issued before `password_changed_at`;
  - the §1.7 profile as a side task.
- **After Phase 2:** OpenAPI docs, with `/v3/api-docs` and `/swagger-ui` public in dev only.
- **Phase 9:**
  - asynchronous email sending, which removes most timing leaks;
  - a real email sender for `prod`, which **refuses to start until then**, on purpose;
  - the cleanup job for expired tokens and 12-month-old events.
- **Phase 10:** rate limiting, against email bombing, lockout abuse and guessing from one IP.
- **Phase 11:** trusting forwarded headers behind a real proxy.

### Accepted behaviour

- Two quick clicks on resend can send two emails; only the newest link works.
- The fifth wrong password writes `ACCOUNT_LOCKED` a moment before its `LOGIN_FAILED`, so newest-first history shows the failure above the lock.
- `user_tokens` keeps revoked rows until Phase 9's cleanup job.

### Small fixes, whenever convenient

- **Tests and config:**
  - The slice tests mock the concrete `TaskflowUserDetailsService`; mock the `UserDetailsService` interface instead. `class` also sits on its own line in `TaskflowSecurityConfigTest`.
  - `UserAuthenticationChecks` should say, in a comment, that Spring's account-expired and credentials-expired checks were dropped on purpose.
  - The CSRF comment in `TaskflowSecurityConfig` runs two ideas together.
  - A commented-out `TRACE` line is still in `application-dev.yml`.
- **Messages and emails:**
  - The 403 detail text is "Email Not Verified"; something like "Verify your email address before signing in" helps the user more.
  - `@ValidPassword`'s message key has no entry in `ValidationMessages.properties`.
  - The "password changed" email could link to the forgot-password page.
- **Code:**
  - `changePassword` uses `catch (Exception e)` plus `instanceof`; two specific `catch` clauses would be clearer.
  - The `uk_user_tokens_active` constraint name is written twice.
  - `markEmailVerified` overwrites the first verification time if called again.
  - `verify` and `resend` would read better in their own `EmailVerificationService`.
- **Leftovers from Phase 0 and §1.2:** an unused import in `AuditAwareImpl`; unused `setRole` / `setTimezone` methods in `UserAccount`; `Organization`'s no-argument constructor should be `protected`.
