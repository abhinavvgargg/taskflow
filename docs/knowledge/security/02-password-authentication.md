# Security 2. Password authentication and the principal

> How a password check runs, how passwords are stored, who "the signed-in user" is, and re-authentication for sensitive actions. Sources: [../SOURCES.md](../SOURCES.md).

## Four parts, four jobs

The **`AuthenticationManager`** picks a provider that can handle the credentials; the **`DaoAuthenticationProvider`** runs the check; **`TaskflowUserDetailsService`** only *finds* the user (by email); the **`PasswordEncoder`** only *compares* the password with the stored hash.

## The provider's order, and what it reveals (*verified*, Spring Security 6.5.11)

Load the user → **pre-checks** (by default: locked? disabled?) → compare the password (also when a pre-check failed, so timing looks normal; the pre-check's error wins) → **post-checks**. "User not found" is hidden as bad credentials, and a dummy password is hashed for unknown users.

With Spring's defaults, anyone with only an email learns "locked" or "unverified". *Seen (Phase 1 deliberate failure):* unverified bob with a **wrong** password got 403 "email not verified". TaskFlow's rule (Phase 1 decision 17): "locked" stays a pre-check but answers the same 401 as a wrong password; "unverified" moves to the post-checks, so only someone with the right password learns it. "Locked" can't be shown only after a correct password either: a guesser during a lockout would recognise the right guess by the different answer.

## One provider bean, and Spring builds the manager

Boot builds the main `AuthenticationManager` from a single `AuthenticationProvider` bean and gives it an event publisher (*verified*). The documented `new ProviderManager(provider)` has **no publisher** (lockout events never fire) and isn't used by the other authentication filters. **Rule:** one `DaoAuthenticationProvider` bean with TaskFlow's checks; the `AuthenticationManager` bean returns the one Spring built (the startup warning "UserDetailsService beans will not be used…" is expected).

## Events

Each check publishes exactly one event, synchronously, before the request continues: a wrong password and an unknown email both give `AuthenticationFailureBadCredentialsEvent`; locked/disabled give others; the event carries the email **as typed**, so the listener normalises it. **Every successful bearer request also publishes `AuthenticationSuccessEvent`** (*verified*, Phase 2), so the lockout listener reacts only to the password principal type, or the owner's open tab would reset the counter while an attacker guesses.

## `UserDetails` pitfalls

- **Since 6.3, `isEnabled()` and `isAccountNonLocked()` default to `true`.** *What happened to us:* the principal had the flags but didn't override the methods; locked and unverified users would have signed in. The lock check was also written backwards (`!now.isAfter(lockedUntil)`): users in *during* the lock, locked forever after.
- **`getUsername()` returns whatever the user signs in with — the email** — and `Authentication.getName()` calls it. *What happened to us:* `created_by` filled with emails ([jpa 07](../jpa/07-auditing-and-time.md)).

## Storing passwords

- **bcrypt** (deliberately slow; salted, the salt stored inside the hash), stored as **`{bcrypt}$2a$10$…`**: the prefix tells `DelegatingPasswordEncoder` which algorithm made it, so a new default can be adopted without resetting everyone (re-hash on the next successful login). A `CHECK` requires the prefix; the column is `varchar(200)`.
- **72 bytes, not characters** (*tested on a copy, two versions*): bcrypt uses only the first 72 bytes. On 6.5.11 `encode()` **throws** past that (a 500 at registration, *seen*); on 6.3.1 it **silently truncated**, so a different password sharing the first 72 bytes logged in. `@MaxUtf8Bytes(72)` on every password field turns it into a 400; login needs the cap too (`matches()` doesn't throw, it compares the first 72 bytes).
- **Rules (Phase 1 decision 9):** at least 12 characters, at most 72 bytes, no composition rules (length is strength); one annotation, `@ValidPassword` ([spring 02](../spring/02-web-layer-validation-and-errors.md)).
- **Validation, duplicate and token checks run before any bcrypt,** so garbage requests don't burn ~100 ms of CPU each. The shared test password is hashed once per test run.

## The principal

- **Never the entity** (Phase 1 decision 4): it lives in the security context for the whole request, outside any transaction ([jpa 01](../jpa/01-entities-and-mapping.md)).
- **Phase 1:** `TaskflowPrincipal` (id, username, email, hash, authorities, flags), used only while a password is checked.
- **Phase 2:** every bearer request carries an **`AuthenticatedUser(id, username, role, sessionId)`** record built from the token's claims, never the database: no email (not in the token), no hash, no flags. Re-authentication therefore **loads the email by id**; never add PII to the token to save a query.
- **Everything that reads the principal follows its type:** `@AuthenticationPrincipal(errorOnInvalidType = true)` fails loudly; the auditor fails silently (`system`) (*seen*, §2.2 deliberate failures 4, 5). The tenant gate refuses any other type (500, by design).
- **Usernames and emails** are accepted in any case and stored lowercase (`Locale.ROOT`); the username never contains `@`, so an email can't be mistaken for one.

## Re-authentication ("sudo mode")

Irreversible actions ask for the current password again even inside a valid session: changing the password (Phase 1) and transferring an organization (Phase 3). A stolen 15-minute access token proves "this browser logged in recently", the password proves "the owner is at the keyboard now".

- **Through the `AuthenticationManager`,** so a wrong guess counts toward lockout like a login. *What happened to us (Phase 1, review):* the first version passed the stored **hash** as the typed password: every change failed and counted, and after five tries users locked themselves out.
- **One implementation:** `user.Reauthentication.confirmPassword(caller, password, clientInfo)` (since §3.2), shared by change-password and transfer; it catches `BadCredentialsException | LockedException` (a locked account gets the same "incorrect password", so a token thief can't learn it's locked) and lets anything else propagate. A wrong password is **400 `CURRENT_PASSWORD_INCORRECT`, `field: currentPassword`**, not 401.
- **Outside any transaction** (bcrypt ~100 ms must not hold a pooled connection) and **after the cheap checks** (owner? not yourself?), so a member's wrong password never counts toward their own lockout (Phase 3 rule A12).
- **A correct re-authentication resets the failure counter,** like any successful login (expected).
- **Accepted risks (§3.2 re-review):** a stolen access token can lock the owner out with 5 wrong transfer passwords, as through change-password (Phase 10's rate limiting); `MembershipService.transferOwnership` is `public`, so a future caller could skip the password (package-private would keep sudo mode unskippable; open nit).

## Interview questions

1. **What do the manager, provider, user service and encoder each do?** Pick a provider; run the check; find the user; compare the password.
2. **How does your login avoid revealing whether an account exists or is locked?** Unknown, wrong and locked give the same 401; unverified is revealed only after the right password.
3. **Why can't you use `@Size(max = 72)` for a bcrypt password?** `@Size` counts characters; bcrypt's limit is bytes.
4. **How would you move everyone to a new hashing algorithm?** The `{id}` prefix: new default for new hashes, re-hash on next successful login.
5. **Why ask for the password again before a transfer?** A stolen token mustn't be able to give the organization away; checked through the manager so guesses count, after cheap checks, outside the transaction.
6. **`hasRole` vs `hasAuthority`?** `hasRole("ADMIN")` adds `ROLE_`.
