# Phase 1 — Users & Auth Core (Summary)

> **One page for the whole phase:** what was built, what it taught, what was decided, and what to say about it in an interview. Built 2026-09-24 → 2026-10-01.
> **Where the detail lives:** the brief for each sub-section with its traps and deliberate failures ([PHASE_1_REQUIREMENTS.md](PHASE_1_REQUIREMENTS.md)), the full record of lessons by theme with all 55 interview questions ([PHASE_1_LEARNING_LOG.md](PHASE_1_LEARNING_LOG.md)), the rebuild recipe and framework facts ([PHASE_1_IMPLEMENTATION_REFERENCE.md](PHASE_1_IMPLEMENTATION_REFERENCE.md)), how to test access rules ([SECURITY_TESTING_GUIDE.md](SECURITY_TESTING_GUIDE.md)), and the topic pages in [`knowledge/`](../knowledge/README.md). This summary links to them and doesn't replace them.

---

## 1. The phase in one paragraph

Phase 1 built identity you can trust. A person can sign up, prove they own their email, sign in, and get locked out after five wrong passwords (unlocked automatically after 15 minutes). They can reset a forgotten password and change a known one. Every request knows who is calling, audit columns record a username (never an email), and security-relevant actions go into a log the database refuses to edit. HTTP Basic was the stand-in transport until Phase 2 replaced it with JWT. Most of the work, and most of the lessons, were about **the failure path**, because a login that returns 200 for the right password is about 20% of the job:
- what a wrong password, an unknown email or a locked account reveals;
- what happens when one link is used twice at the same moment;
- what survives a rolled-back transaction.

The phase's main lesson: **you need to know exactly when the database does something** (save vs flush vs commit, whole-row writes, rollback-only). Most of the silent bugs came from not knowing.

---

## 2. What we built

| § | Built | Key classes | Changed later | Mechanisms explained in |
|---|---|---|---|---|
| 1.1 | The security gate: filter chain, deny-by-default access rules, stateless HTTP Basic, public health (details admin-only), CSRF and logout off with reasons | `TaskflowSecurityConfig` | Phase 2: Basic → bearer JWT; 401/403 now in `ProblemDetail` format | [security 01](../knowledge/security/01-filter-chain-and-access-rules.md) |
| 1.2 | Accounts (`user_accounts`), bcrypt with `{bcrypt}` prefix, a small principal, auditor writing the username | `UserAccount`, `TaskflowUserDetailsService`, `TaskflowPrincipal`, `AuditAwareImpl` | Phase 2: the request principal comes from the token | [security 02](../knowledge/security/02-password-authentication.md), [jpa 01](../knowledge/jpa/01-entities-and-mapping.md) |
| 1.3 | Registration: 201, a 409 that names the clashing field (even under a race), one password rule | `UserRegistrationService`, `@ValidPassword`, `@MaxUtf8Bytes` | — | [spring 02](../knowledge/spring/02-web-layer-validation-and-errors.md), [jpa 02](../knowledge/jpa/02-persistence-context-and-flushing.md) |
| 1.4 | Email verification: one-time hashed link, resend that always answers 202, emails sent after commit | `UserTokenService`, `RegistrationWorkflow`, `AccountEmails` | — | [security 03](../knowledge/security/03-email-link-tokens.md), [jpa 03](../knowledge/jpa/03-transactions.md) |
| 1.5 | Login (same 401 for unknown/wrong/locked), lockout (5 wrong → 15 min) through every path, the append-only security log, login history | `LoginService`, `AuthenticationEventsListener`, `LoginAttemptService`, `SecurityEvent`, `SecurityEventRecorder`, `LoginHistoryService` | Phase 2: login also issues tokens and a refresh cookie | [security 05](../knowledge/security/05-account-protection-and-privacy.md), [jpa 05](../knowledge/jpa/05-bulk-updates-and-concurrency.md) |
| 1.6 | Password reset (30-min link, unlocks and verifies, emails the owner) and change (re-checks the current password, cancels any reset link) | `PasswordWorkflow`, `PasswordService` | Phase 2: reset ends every session, change ends the others · Phase 3 §3.2: re-authentication moved to `Reauthentication` | [security 02](../knowledge/security/02-password-authentication.md), [security 05](../knowledge/security/05-account-protection-and-privacy.md) |
| 1.7 | Profile | — | **Not built.** Moved to Phase 2 (§2.6), now Phase 3 §3.5 | — |

**Migrations:** V2–V4 (`user_accounts`, `user_tokens`, `security_events`), frozen.

**Endpoints:**

| Endpoint | Behaviour |
|---|---|
| `POST /auth/register` | 201 |
| `POST /auth/verify-email` | 204 |
| `POST /auth/verify-email/resend` | Always 202 |
| `POST /auth/login` | 200 / 401 / 403 |
| `POST /auth/password-reset/request` | Always 202 |
| `POST /auth/password-reset/confirm` | 204 |
| `PUT /users/me/password` | 204 |
| `GET /users/me/login-history` | 200, paginated |

The full table with error codes is in [reference Part E](PHASE_1_IMPLEMENTATION_REFERENCE.md).

---

## 3. Concepts learned

**How Spring Security decides who gets in** → [security 01](../knowledge/security/01-filter-chain-and-access-rules.md)
- **The request path:** `DelegatingFilterProxy` (Tomcat → Spring bridge) → `FilterChainProxy` (picks a chain, clears the context afterwards) → the chain's filters → `DispatcherServlet`. Our `CorrelationIdFilter` runs before all of it, so rejected requests still carry an id.
- **401 means "I don't know who you are"; 403 means "I know you, and no."** `denyAll()` gives an anonymous caller 401, because it can't forbid someone it hasn't identified.
- **Close everything you didn't open.** End with `anyRequest().denyAll()`, not `.authenticated()`, which means "any signed-in user may call anything", and anyone can sign up. Rules are checked top to bottom, first match wins.
- **URL patterns are only checked when a request arrives.** A broken rule starts cleanly and then 500s on real traffic.
- **`permitAll()` ≠ `anonymous()`.** `anonymous()` refuses signed-in callers.
- **Errors make a second trip to `/error`** through the filter chain, so `/error` must be public or every error becomes an empty 401.
- **Declaring your own beans switches Boot's defaults off:**
  - a `SecurityFilterChain` bean removes the default chain;
  - a user service, provider or manager removes the generated user.
- **CSRF matters only when the browser attaches credentials by itself** (cookies, cached Basic). It's off for an API, with the reason written next to it.
- **Health:** the endpoint is public (probes call it anonymously) and only its **details** are admin-only. `show-details: when_authorized` without a role means *any* signed-in user sees them.

**How a password check works** → [security 02](../knowledge/security/02-password-authentication.md)
- **Four parts, four jobs:**
  - the `AuthenticationManager` picks a provider;
  - the `DaoAuthenticationProvider` runs the check;
  - the `UserDetailsService` only *finds* the user;
  - the `PasswordEncoder` only *compares*.
- **The order of checks decides what leaks:** load → pre-checks → compare password → post-checks. Spring's defaults reveal "locked" or "unverified" from an email alone. We keep *locked* as a pre-check with the generic 401 and moved *unverified* after the password.
- **Exactly one provider bean, and let Spring build the manager.** Spring's own manager has an event publisher and is the one Basic uses. A hand-made `ProviderManager` has neither, so lockout events never fire.
- **`UserDetails.isEnabled()`/`isAccountNonLocked()` default to `true`** (since 6.3), so the principal must override them.
- **`getUsername()` returns whatever the user signs in with (the email).** The auditor reads our own `getAppUsername()` instead. Roles need the `ROLE_` prefix for `hasRole`.
- **The principal is a small immutable object, never the entity.** It lives for the whole request outside any transaction, where an entity would be a stale copy with lazy fields waiting to throw.
- **Re-check the password before sensitive changes, through the same manager**, so wrong guesses count toward lockout.

**Passwords and input** → [security 02](../knowledge/security/02-password-authentication.md), [spring 02](../knowledge/spring/02-web-layer-validation-and-errors.md)
- **bcrypt is slow and salted.** The `{bcrypt}` prefix lets `DelegatingPasswordEncoder` move to a new algorithm later without resetting anyone.
- **bcrypt reads 72 *bytes*, not characters.** An emoji is 4 bytes but 2 Java chars. On 6.5.11 `encode()` throws above 72 bytes, a 500; on 6.3.1 it silently truncated. Hence `@MaxUtf8Bytes(72)`.
- **Custom constraints:**
  - an annotation plus a validator, treating `null` as valid;
  - messages go in `ValidationMessages.properties` (plural);
  - composing rules into one annotation needs `ElementType.ANNOTATION_TYPE`.
- **Normalise once at the top** (`toLowerCase(Locale.ROOT)`; the Turkish `I → ı` trap), then check and save the same values.
- **A record's `toString()` prints every field.** Requests holding secrets or emails override it.

**Links in emails (tokens)** → [security 03](../knowledge/security/03-email-link-tokens.md)
- **Six properties, each blocking one attack:**
  - unguessable: 32 bytes from `SecureRandom`;
  - stored only as a hash;
  - expiring;
  - single-use;
  - bound to one purpose;
  - cancelled when a new one is issued.
- **SHA-256 for tokens, bcrypt for passwords.** A 256-bit random value can't be guessed at any speed, and the row must be findable by its hash, which bcrypt's random salt prevents.
- **Single use under concurrency:**
  - one conditional `UPDATE … WHERE consumed_at IS NULL …`;
  - Postgres runs it atomically, so the row count (1 or 0) is the answer.
- **The token goes after `#` in the link.** Browsers never send the fragment to a server, and using it needs a POST, so mail scanners can't spend it.
- **Lifetime matches the damage:** verification 24 h, reset 30 min.

**Transactions and persistence** → [jpa 02](../knowledge/jpa/02-persistence-context-and-flushing.md), [jpa 03](../knowledge/jpa/03-transactions.md)
- **`save` ≠ flush ≠ commit.**
  - `save` only remembers the entity.
  - Flush sends SQL inside the open transaction.
  - Only commit makes it real.
  - Use `saveAndFlush` when a constraint error must surface inside your `try`.
- **After a database error, the transaction is rollback-only.** Catching it and returning normally gives `UnexpectedRollbackException` at commit. Catch-to-continue belongs **outside** the transaction (the workflow classes).
- **Irreversible side effects (email) happen after commit, from a different class.** Otherwise:
  - a failed commit leaves a "phantom email" pointing at nothing;
  - a call through `this` skips the `@Transactional` proxy entirely.
- **Propagation:**
  - `REQUIRED` joins or starts a transaction.
  - `MANDATORY` refuses to run alone; used for tokens, so they commit with what they belong to.
  - `REQUIRES_NEW` commits on its own; used for the lockout counter. It costs a second connection.
- **A record is saved with what it records.** Failed attempts commit on their own so they survive; state changes (locked, verified, reset) commit with the change.
- **Hibernate writes the whole row** with the values it loaded, overwriting concurrent changes. `@DynamicUpdate` writes only changed columns.
- **`@Modifying` queries skip the persistence context and auditing.**
- **Spring Data ignores the words between `find` and `By`.** `findIdByEmail` loads the whole entity, so write a `@Query` for projections.
- **Database `DEFAULT`s don't apply to Hibernate inserts**, because it sends every column. A record's accessor is `null` inside its compact constructor.

**Not revealing who has an account, and lockout** → [security 05](../knowledge/security/05-account-protection-and-privacy.md), [jpa 05](../knowledge/jpa/05-bulk-updates-and-concurrency.md)
- **Any difference in status, body or timing is an oracle** that turns your endpoint into an email checker:
  - Registration reveals it on purpose.
  - Login gives one 401 for unknown, wrong and locked.
  - Resend and reset always answer 202.
  - Timing still leaks a little until Phase 9's async email.
- **A lockout counter has to be right while it's under attack:**
  - increment in SQL, not Java;
  - a second conditional statement applies the lock, and its row count tells exactly one request "you locked it";
  - the listener fails loudly, so an uncounted guess never gets through.
- **Lockout can be turned against the owner.** The auto-unlock limits it; per-IP rate limiting (Phase 10) is the real fix.

**The database as the last line of defence** → [jpa 06](../knowledge/jpa/06-schema-constraints-and-invariants.md)
- **`CHECK` constraints catch what code forgets:**
  - lowercase emails;
  - no `@` in usernames;
  - the hash prefix;
  - 64-hex token hashes;
  - valid event/reason pairs.
- **Constraint names under 63 characters**, or Postgres truncates them and error mapping breaks.
- **`inet` for IPs:** it validates and canonicalises.
- **Every foreign key gets an index and an `ON DELETE` rule.**
- **An append-only table needs two guards:** `@Immutable` (silent) plus a trigger that rejects `UPDATE` (loud).

**Logs, personal data, the client's IP** → [security 05](../knowledge/security/05-account-protection-and-privacy.md), [spring 04](../knowledge/spring/04-observability-and-operations.md)
- **Logs carry ids, never emails, passwords or tokens.**
- **The client IP is `getRemoteAddr()`.** `X-Forwarded-For` is a header anyone can set.
- **Boot turns forwarded-header handling on by itself on cloud platforms.**
- **JSON field names are a contract** once a client depends on them.

**Testing security** → [testing 01](../knowledge/testing/01-test-strategy-and-slices.md), [testing 02](../knowledge/testing/02-security-race-and-time-tests.md)
- **A security slice with no controllers:** "allowed" shows up as 404, "blocked" as 401/403. It silently can't test actuator rules or real credentials; those go in the integration test.
- **`with(user(…))` vs `httpBasic(…)`:**
  - `with(user(…))` skips the password check and tests authorization only;
  - `httpBasic(…)` goes through the real check.
- **Test as the weakest role that should succeed**, and pair each rule with the nearest caller who shouldn't.
- **Test config belongs in `src/test`.** In `src/main` it ships in the production jar.
- **Fixed `Clock` for time rules; clear `SecurityContextHolder` after tests that set it; prove every test can fail.**

---

## 4. Decisions

The decisions you'd have to defend. The full list (28 in the requirements table, 47 in the log) is indexed by topic in [decisions/INDEX.md](../knowledge/decisions/INDEX.md).

| Decision | Chosen | Why | What we turned down, and why |
|---|---|---|---|
| Transport before JWT | Stateless HTTP Basic + `POST /auth/login` | Signed-in endpoints could be built and tested now; login is the seam Phase 2 extended | Server sessions: thrown away in Phase 2 anyway. No protected endpoints: nothing to test until Phase 2. |
| Sign-in identifier | Email, **plus** a separate username | Audit columns hold the username (survives an email change, no PII); Phase 9 @mentions need one | Email only: personal data in every `created_by` and broken history on email change |
| Principal | A small immutable `TaskflowPrincipal` in `common/security` | Lives outside any transaction; the auditor in `common` must read it | The entity as `UserDetails`: a stale detached copy with lazy fields that throw |
| Platform role | One `role` column (`USER`/`ADMIN`) | One fact per user | A join table: an extra collection load on every request; many-roles belongs to orgs/projects (Phases 3–4) |
| Password rule | ≥ 12 characters, ≤ 72 bytes, no composition rules, in one `@ValidPassword` | Length is what makes passwords strong; 72 bytes is bcrypt's real limit | Symbol/digit rules: weaker passwords in practice. Min length on the login form: tightening it later would lock out old passwords |
| Duplicate email at registration | 409 `EMAIL_ALREADY_REGISTERED` | Much clearer for someone who forgot they have an account | Always 202 + email the owner: a worse experience; rate limiting (Phase 10) handles mass probing |
| Tokens | One `user_tokens` table; old links **revoked** + a partial unique index (one live link per user and purpose) | The database itself guarantees one live link. *Your improvement over the brief* | "Delete old tokens": only code enforces it. This is where the "recommend the best approach" rule came from |
| Token in the link | URL fragment (`#token=…`), sent back by POST | Never in access logs, proxy logs or `Referer` | A query parameter: logged everywhere the URL passes |
| Sending emails | After commit, from a separate non-transactional workflow bean; a failed send is logged, the request still succeeds | No phantom emails; no transaction held open on the mail server | Sending inside the transaction (phantom email). Same-class call (proxy skipped). Phase 9 replaces it with `AFTER_COMMIT` events |
| What a failed login reveals | Unknown / wrong / locked → identical 401; unverified → 403 **only after the right password** | No account-state oracle without the password | Spring's defaults (leak state from an email). "Locked" only after a correct password (reveals the right guess during a lockout) |
| Lockout | 5 wrong → 15 min; counter resets when the lock applies; driven by authentication events in `REQUIRES_NEW` | Basic counts too; the count survives the failed login's rollback | Counting in the login endpoint: Basic becomes a side door. Escalating locks: a worse denial of service without stopping the attacker |
| Security log | `security_events`: `inet`, fixed event types, reason only on failures, nothing for unknown emails, `ON DELETE CASCADE`, 12-month retention (stance) | Valid IPs, no rows about non-users, erasure actually erases | A separate `login_events` table: same cost, closes less of the Phase 0 audit-log debt |
| Append-only log | Id-only `@MappedSuperclass` split out of `BaseEntity`; `@Immutable` + a trigger rejecting `UPDATE` | No meaningless `updated_*` columns; any writer's mistake is loud | `@Immutable` alone: ignores changes silently |
| Wrong current password | 400 `CURRENT_PASSWORD_INCORRECT`, counts toward lockout | One wrong form field; closes "stolen session + unlimited guesses" | 401: a Phase 2 client would sign the user out |
| Reset side effects | Unlocks, verifies the email, notifies the owner; link lives 30 min | Mailbox control proves more than a guess; a takeover gets noticed | Leaving the lock: the correct new password gets 401 for up to 15 minutes |
| Whole-row writes | `@DynamicUpdate` on `UserAccount`; account changes through entity methods | Verified that the alternatives undo an unlock or lose the password | `clearAutomatically = true`: lost the password change silently |
| §1.7 profile | Moved to Phase 2 | Phase 1 was past its time-box; nothing depended on it | Extending the phase: against the time-box rule |

---

## 5. Patterns to reuse

1. **Judge security code on the failure path.** Every rule gets a test for the caller who should be refused.
2. **Close everything you didn't explicitly open**, and list public paths exactly (method included).
3. **The same answer for every way to fail** when a difference would reveal who has an account or what state it's in.
4. **Let one SQL statement arbitrate a race:** a conditional `UPDATE` and its row count, or a unique/partial index.
5. **Irreversible side effects after commit, from another bean.** Catch-to-continue outside the transaction.
6. **Choose propagation on purpose:** `MANDATORY` for building blocks, `REQUIRES_NEW` for records that must survive a rollback.
7. **Security counters fail loudly.** A counter that can silently stop counting is worse than none.
8. **Normalise once, then let the database refuse anything that slipped through.**
9. **Inject the `Clock`.** Pass times into entities and queries, never `Instant.now()` inside them.
10. **Secrets and personal data never reach logs.** Mask `toString()` and log ids.
11. **`common` never imports a feature.** It happened twice this phase (`TokenProperties`, the security config).

---

## 6. Bugs, traps and deliberate failures

**What happened to us.** All of these compiled and most started cleanly; the full list is in [learning log §3](PHASE_1_LEARNING_LOG.md).

| Bug | What happened | Rule now |
|---|---|---|
| Public paths written as `"/auth/{register, login, …}"`; actuator rule given a path instead of a name | Every POST, then every GET, returned 500, after a clean startup | Every access rule is a row in a test |
| `anonymous()` on health | A signed-in admin got 403 | `permitAll()` for public endpoints |
| Auditor used `authentication.getName()` | `created_by` filled with email addresses | Read the app username from our principal |
| Principal didn't override `isEnabled()`; lock check written backwards | Locked and unverified users would have signed in; the lock would have applied *after* expiry | Override the defaults; test the boundary instant |
| Change-password passed the stored hash as the typed password | Every change failed, and after five tries the user was locked out (caught in review) | Pass what the user typed |
| `findIdByEmail` derived query + a listener that swallowed errors | Threw for every real account, so lockout was silently off; every response still a normal 401 | Write projection queries; fail loudly |
| SQL unlock, then an entity save | The lock came back; with `clearAutomatically`, the new password was lost | `@DynamicUpdate` + entity methods |
| Verification email sent inside the registration transaction | Would produce phantom emails on a failed commit (caught in review) | Send after commit |
| Registration checked duplicates with the raw email, saved the lowercased one | Duplicates reached the constraint; the wrong error code could win | Normalise first |
| A record compact constructor read its own accessor | `null` → every startup crashed | Use the parameter |
| `application-test.yml` (with an admin user) in `src/main` | One profile setting away from a known admin login in production | Test config lives in `src/test` |
| A pushed commit referenced an unstaged test class | A fresh clone didn't compile | Stage new files by name |

**Traps worth remembering**
- **`@Size(max = 72)` counts chars, not bytes.**
- **The same mistake shows different status codes in a slice and in an integration test:** 401/403 vs 401/**302**, because real Tomcat makes the `/error` trip and MockMvc doesn't.
- **IntelliJ's "delegate to Maven" runs the tests before starting the app**, so one red test means no app.
- **`spring-boot:run` doesn't reload code.**
- **`psql -c` mangles the `$` signs in a bcrypt hash.**

**Deliberate failures:** broken on purpose to see the error.

| Broke | What you saw | Lesson |
|---|---|---|
| Only the security starter, no config | 6 of 13 tests red; a generated password in the log | Boot's default chain, and how one cause looks different per test type |
| Spring's default account checks; unverified bob, **wrong** password | 403 "email not verified": state leaked without the password | The order of checks decides what leaks |
| Lockout counter in the login's own transaction | After the wrong passwords, the counter was still 0, with no error | `REQUIRES_NEW` and the rollback trap |
| Logged a request record | Plaintext password in the log | Records print every field |
| 19 emoji with `@Size(max = 72)` | 500 from the encoder; with `@MaxUtf8Bytes`, a clean 400 | Characters aren't bytes |
| *(On a copy)* SQL unlock → save / `clearAutomatically` / `@DynamicUpdate` | Lock back / password lost / both kept | Hibernate writes the whole row |

Planned but not run (listed in [learning log §7](PHASE_1_LEARNING_LOG.md)): the registration race with `save` vs `saveAndFlush`, the phantom email on purpose, the Java-side counter losing updates, trusting `X-Forwarded-For`, and others.

---

## 7. Interview questions

Short answers, the way you'd say them. All 55 are in [learning log §5](PHASE_1_LEARNING_LOG.md).

1. **What happens between a request arriving and your controller running?** "Tomcat hands it to `DelegatingFilterProxy`, which passes it to `FilterChainProxy`. That picks the matching security chain and runs its filters: security context, headers, credentials, anonymous, exception translation, then the access rules. Only then does it reach the `DispatcherServlet`."
2. **Why isn't `anyRequest().authenticated()` deny by default?** "It means any signed-in user can call any endpoint nobody declared, and with self-registration anyone can be signed in. I end with `denyAll()`, so a forgotten endpoint is closed."
3. **What do the manager, provider, user service and encoder each do?** "The manager picks a provider. The provider runs the check. The user service only finds the user, and the encoder only compares the password with the hash."
4. **Why doesn't your entity implement `UserDetails`?** "The principal lives for the whole request outside any transaction. An entity there is a stale detached copy with lazy fields waiting to throw. I use a small immutable principal."
5. **Why can't you use `@Size(max = 72)` for a bcrypt password?** "`@Size` counts Java characters, bcrypt's limit is 72 bytes, and an emoji is two characters but four bytes. On our version an over-long password makes the encoder throw; on an older one I tested, everything after byte 72 was ignored, so a different password could sign in."
6. **How would you design a reset or verification token?** "32 random bytes from `SecureRandom`, only its SHA-256 hash stored, with an expiry, single use and a purpose. Issuing a new one cancels the old. It travels in the URL fragment, comes back in a POST, and is never logged."
7. **How do you make a token single-use under simultaneous requests?** "One conditional `UPDATE` that marks it used only where it's still unused, unexpired and unrevoked. Postgres runs it atomically, so exactly one request updates a row."
8. **Why not send the email inside the transaction?** "If the commit then fails, the user has a link to something that doesn't exist. And the transaction holds a connection for as long as the mail server takes."
9. **You caught the exception, so why did the transaction still fail?** "A database error marks the transaction rollback-only. Catching it doesn't clear that, so the commit throws `UnexpectedRollbackException`. To carry on, catch it outside the transaction."
10. **How does your lockout counter survive a failed login rolling back?** "A listener for Spring's authentication events writes it in its own `REQUIRES_NEW` transaction. I tried it the other way on purpose: the counter stayed at zero."
11. **How does your login avoid revealing whether an account exists?** "Unknown email, wrong password and locked account all get the same 401 with the same body, and all cost one bcrypt. Only someone with the correct password learns the email isn't verified."
12. **Why doesn't a locked account get a "locked" message?** "If 'locked' showed only after a correct password, an attacker guessing during a lockout would recognise the right guess by the different answer."
13. **A reset unlocked the account, then the lock came back. How?** "The unlock was a direct SQL update, but the entity had been loaded earlier, and Hibernate's update wrote back every column, including the old lock. `@DynamicUpdate` writes only what changed."
14. **Why `REQUIRES_NEW` and `MANDATORY`?** "`MANDATORY` for building blocks that must be part of a bigger transaction, like issuing a token during registration. `REQUIRES_NEW` for records that must survive the caller's rollback, like the failed-login counter. It costs a second connection."
15. **How do you make an audit table impossible to edit?** "No setters and `@Immutable` in the app, and a database trigger that rejects every `UPDATE`, because `@Immutable` ignores changes silently instead of failing."
16. **Isn't lockout a denial-of-service risk?** "Yes, anyone who knows your email can lock you out. The automatic unlock limits it; per-IP rate limiting, which slows the attacker instead of the victim, is the real fix."

---

## 8. Numbers measured

| What | Result |
|---|---|
| Test suite at phase close | **76 tests**, about 13 s, 2 containers. They only cover §1.1–§1.2 and Phase 0 (13 → 45 after §1.1 → 76 after §1.2) |
| Context reuse | The security integration test ran 13 tests in 0.44 s without starting a second application |
| Mutation checks (§1.1–§1.2) | **15 of 15** caught; e.g. CSRF back on → 11 tests red, `ORDINAL` role → app refused to start |
| Resend race (dev DB) | 10 simultaneous requests → 4 links issued; 6 detected the collision and answered 202 without a duplicate email |
| Reset-link race | 20 simultaneous uses of one link, reported by you as passing; automated in Phase 3 §3.0: exactly one success |

---

## 9. Deferred and carried debt

Where each item went. Status checked against the code on 2026-10-29.

| Item | Where it went |
|---|---|
| 401/403 from the filter chain in `ProblemDetail` format | ✅ Phase 2 (`ProblemAuthenticationEntryPoint`, `ProblemAccessDeniedHandler`) |
| Other devices after a password change | ✅ Phase 2: reset ends every session, change ends the others |
| Tests for lockout, reset, change and enumeration | ✅ Phase 3 §3.0 (Tier 1): rollback trap, reset race, change keeps the current device, identical 401s; all mutations caught |
| Tests for registration details (409 under a race, password never echoed) and verification details | ⏸️ Tier 2 → Phase 11 |
| Measurements: login timing gap, bcrypt cost 10 vs 12, the registration race | Still owed |
| §1.7 profile | ⏸️ Phase 3 §3.5 |
| A real `prod` email sender (prod refuses to start until then), async sending, cleanup job for old tokens and events | ⏸️ Phase 9 (only `LoggingEmailSender` exists) |
| Rate limiting (email bombing, lockout abuse, guessing from one IP) | ⏸️ Phase 10 |
| Trusting forwarded headers behind a real proxy | ⏸️ Phase 11 |
| OpenAPI | ⏸️ Phase 11 |
| Email change | ⏸️ Backlog, design written in `PROJECT_CONTEXT.md` §6 |
| Accepted behaviour | Two quick resends can send two emails (only the newest works); `ACCOUNT_LOCKED` is written a moment before its `LOGIN_FAILED`; revoked tokens stay until Phase 9 |
| `RegisterRequest.toString()` prints the email and username | Open |
| 403 detail text "Email Not Verified" doesn't tell the user what to do | Open |
| `markEmailVerified` overwrites the first verification time | Open |
| `UserAuthenticationChecks` doesn't say why the expired checks were dropped | Open |
| `setRole` / `setTimezone` on `UserAccount` | Now `private`; still present (the profile replaces `setTimezone`, Phase 3 §3.5) |
| `changePassword`'s `catch (Exception e)` + `instanceof` | Gone: no `instanceof` left in `PasswordWorkflow` (re-authentication moved to `Reauthentication`, Phase 3 §3.2) |
| Slice tests mocking the concrete `TaskflowUserDetailsService` | Gone: no test mocks a user service any more |
| `@ValidPassword` message key with no `ValidationMessages.properties` entry | Gone: it now uses a literal message (`password must be at least 12 characters`) |
| `Organization`'s public no-arg constructor | ✅ Phase 3 §3.1 |
| Your 15-minute Phase 1 notes | Template at the end of the requirements doc is still empty |

---

## 10. Read more

- **Rebuild a feature:** [reference Part D](PHASE_1_IMPLEMENTATION_REFERENCE.md) (step-by-step recipe) and [features/INDEX.md](../knowledge/features/INDEX.md) (Phase 1 rows → reference C1–C12)
- **Framework behaviour we relied on, checked in the source:** [reference Part F](PHASE_1_IMPLEMENTATION_REFERENCE.md)
- **Why we decided X:** [decisions/INDEX.md](../knowledge/decisions/INDEX.md)
- **A mechanism in depth:** [knowledge/README.md](../knowledge/README.md); [knowledge/SOURCES.md](../knowledge/SOURCES.md) and [knowledge/jpa/SOURCES.md](../knowledge/jpa/SOURCES.md) map each Phase 1 lesson to its page
- **Commands** (curl flows, races, dev SQL, reading framework source, debug logging switches): [learning log §6](PHASE_1_LEARNING_LOG.md)
- **Testing access rules:** [SECURITY_TESTING_GUIDE.md](SECURITY_TESTING_GUIDE.md)
- **The foundations this builds on:** [Phase 0 summary](../phase-0/PHASE_0_SUMMARY.md)
