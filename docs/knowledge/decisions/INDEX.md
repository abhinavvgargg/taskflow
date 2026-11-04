# Design decisions — index (all phases)

> Every decision taken in TaskFlow, **grouped by topic** so "why did we do X?" is one lookup, whatever phase it came from. One line each; the dated record, with every option and why it lost, stays in its phase's Decisions table (the **Record** column). Built 2026-10-27 from Phase 0–3's tables, learning logs, implementation references and the §3.3 brief.
>
> **IDs:** `P1-12` = Phase 1, decision 12 · `P2-D11` = Phase 2, D11 · `(build)` = chosen while building (recorded in a learning log or a *Built* section) · `(brief §3.3)` = in the §3.3 brief, not built yet · *your approach* = you proposed it and it beat the brief.

**Records:**
[P0 table](../../phase-0/PHASE_0_REQUIREMENTS.md) (decisions 1–6; 7–13 in [P0 log §0](../../phase-0/PHASE_0_LEARNING_LOG.md)) ·
[P1 table](../../phase-1/PHASE_1_REQUIREMENTS.md) · [P1 log §2](../../phase-1/PHASE_1_LEARNING_LOG.md) ·
[P2 table](../../phase-2/PHASE_2_REQUIREMENTS.md) · [P2 log §2](../../phase-2/PHASE_2_LEARNING_LOG.md) ·
[P3 table](../../phase-3/PHASE_3_REQUIREMENTS.md) (full D1–D8 write-ups in *🏗️ Decisions — raised first*) · [P3 log §2](../../phase-3/PHASE_3_LEARNING_LOG.md)

---

## 1. Project structure and code organisation

| ID | Decision | Chosen | Why, in one line | Record |
|---|---|---|---|---|
| P0-1 | Build tool | Maven 3.9.16 | Standard in enterprise Spring shops | P0 table |
| P0-2 | Boot version | 3.5.16 | What tutorials and the job market run; generate with 4.x and downgrade | P0 table |
| P0-3 | Lombok | Full, **never on entities** | `@Data`/`@EqualsAndHashCode`/`@ToString`/`@Builder` break entities | P0 table |
| P0-6 | Java | 21 | The plan says 21; no stacked bleeding edges | P0 table |
| P0-5 | Phase 0 slice | `organizations` | A real table kept for good | P0 table |
| P0-8 | Config format | YAML | Nesting across three profiles | P0 log §0 |
| P0-13 | Error codes | `ErrorCode` interface in `common`, an enum per feature | `common` never imports a feature | P0 log §0 |
| P1-2 | Security package layout | `common/security` + `user` | The principal type must live in `common` (the auditor reads it) | P1 table |
| (build) P1 | `LoginFailedException` location | `common/error` *(your choice, better than the brief)* | Not specific to users | P1 log §2 |
| (build) P2 | `UserRole` location | `common/security` | The JWT converter parses roles | P2 log §3 |
| (build) P2 | JWT converter | A plain object, **not a bean** *(your call)* | Boot adds every `Converter` bean to MVC's conversion service | P2 log §2 |
| (build) P3 | Tenant gate location | `common/tenant`, asking `MembershipLookup` (implemented in `organization`) | Same pattern as `SessionStatus` | P3 req §3.1 |
| (build) P3 | Transfer orchestration | `MembershipWorkflow`, non-transactional, before the transactional service | The password check (bcrypt) runs outside the transaction | P3 req §3.2 |
| (build) P3 | Re-authentication | One `user.Reauthentication`, shared by change-password and transfer (renamed from `AuthenticationUtil`) | One path that counts toward lockout | P3 req §3.2 |

## 2. Persistence: schema, entities, transactions

*Mechanisms: [JPA pages](../jpa/).*

| ID | Decision | Chosen | Why, in one line | Record |
|---|---|---|---|---|
| P0-4 | Primary keys | `bigint` + pooled sequence (`increment by 50` = `allocationSize`) | Batch-friendly, 8-byte FKs; a PK type can't change later | P0 table |
| P0-7 | `id` declaration | One shared `global_id_seq` in a superclass | Less repetition; one number space | P0 log §0 |
| P0-9 | `created_by` | The **username**, not the email | Survives an email change | P0 log §0 |
| P0-10 | Organization slug | Client-supplied | Makes the 409 real; no slugify + collisions | P0 log §0 |
| P0-11 | Entity → DTO | Static factory on the response record | Right size; no MapStruct | P0 log §0 |
| P0 (brief §0.5) | `@Version` in `BaseEntity` | **No**; only on specific entities (Phase 5) | Blanket versioning throws `OptimisticLockException` where nobody expects | P0 req §0.5 |
| P1-5 | Platform roles | One `role` column (`USER`/`ADMIN`) | Many-roles belongs to memberships | P1 table |
| P1-8 | Token storage | One `user_tokens` table, `purpose` `CHECK` | Same rules for both purposes | P1 table |
| P1-12 | Old tokens on reissue | **Revoked** + partial unique index *(your approach; was "deleted")* | The database guarantees one live link; rows keep history | P1 table |
| P1-16 | `UserToken` → `UserAccount` | `@ManyToOne(fetch = LAZY)` | No eager load on every token read | P1 table |
| P1 (brief §1.5) | `SecurityEvent` → user | A plain `Long` | An append-only log never navigates | P1 req §1.5 |
| P1-21 | Append-only `security_events` | Id-only superclass; `@Immutable`; a trigger rejects `UPDATE` | `@Immutable` alone is silent | P1 table |
| P1-27 | Whole-row writes on `user_accounts` | `@DynamicUpdate`; changes through entity methods | An entity write undid a bulk unlock | P1 table |
| P1-13 | Send after commit | A separate non-transactional workflow bean | No self-invocation; Phase 9 → after-commit events | P1 table |
| P1-15 | Email fails after save | Log (id only), still 201 | Rolling back needs the send inside the transaction | P1 table |
| P2-D11 | Session entities | **Insert-only**: mutable columns `updatable = false`, conditional updates *(your approach)* | A stale flush can't resurrect a revoked session | P2 table |
| P2-D13 | Dead session at refresh | `setRollbackOnly()` + return *(your approach)* | The consume doesn't commit; no false theft signal | P2 table |
| P3-D2 | Tenancy model | Shared schema, `organization_id` | Self-service signup | P3 table |
| P3-D4 | Isolation | `@TenantId` + gate + composite FKs; RLS optional | `@TenantId` covers `find()` and inserts; RLS fails open in Testcontainers | P3 table |
| (build) P3 | Owner invariant | Partial unique index (≤ 1, immediate) + deferred constraint trigger (≥ 1, at commit) | Every code path, including future ones | P3 req §3.1 (V7) |
| (build) P3 | Memberships | Insert-only; changes carry the expected role in the `WHERE` | No lost updates; a free optimistic lock | P3 req §3.2 |
| (build) P3 | Commit-time constraint names | `CommonErrorUtility` reads raw `SQLException`s with Hibernate's PostgreSQL extractor | Deferred violations had no name in the 409 | P3 req §3.1 |
| (build) P3 | Role-change response | Read back with a projection after the update | Bulk updates leave entities stale | P3 req §3.2 |
| (brief §3.3) | "Now" in queries | A `:now` parameter from the `Clock`, never SQL `now()` | The test clock must reach expiry checks | P3 req §3.3 |
| (brief §3.3) | Re-inviting an email | Revoke the open invitation, then insert | A partial index can't contain `now()` | P3 req §3.3 |

## 3. API and errors

| ID | Decision | Chosen | Why, in one line | Record |
|---|---|---|---|---|
| P0-12 | `Pageable` construction | By hand from `ApiProperties` (`PageableFactory`) | One source of truth | P0 log §0 |
| P0 (build) | Sorting | Per-endpoint whitelist + `id` tiebreaker | `?sort=` was a client-controlled 500 and join | P0 log §2 |
| P0 (build) | Unique-value races | Service check + constraint, both → 409 | The constraint is the guarantee | P0 log §2 |
| P1-7 | Existing email at registration | 409 `EMAIL_ALREADY_REGISTERED` | Clear for users; login/reset still don't reveal it | P1 table |
| (build) P1 | Registration response | 201, no `Location` | No URL where a user could read another user | P1 log §2 |
| P2-D15 | Disallowed cross-origin request | Spring's plain-text CORS 403, accepted | Browsers can't read it; no processor hook | P2 table |
| P3-D3 | Naming the organization | Path `/organizations/{orgId}/…`, membership read per request | Changes act on the next request; tenant in every log | P3 table |
| (build) P3 | Non-member vs non-existent | The same 404, constant detail, no id | Ids are guessable | P3 req §3.1 |
| (build) P3 | Enum-like request fields | A `String` with `@Pattern`, converted in code | Every invalid value → the same 400 on the field | P3 req §3.2 |
| (brief §3.3) | `ALREADY_MEMBER` | A 409 that reveals membership to the org's managers, accepted | The alternative leaves pointless open invitations | P3 req §3.3 |

## 4. Authentication, tokens and sessions

| ID | Decision | Chosen | Why, in one line | Record |
|---|---|---|---|---|
| ~~P1-1~~ → P2-D1 | Authentication before JWT | ~~HTTP Basic + `POST /auth/login`~~ (deleted in Phase 2) | Built and tested `/me` before tokens | P1 table |
| P1-3 | Login identifier | Email, plus a separate username | `created_by` and `@mentions` need a username | P1 table |
| P1-4 | Principal | A small separate class, never the entity | Lives outside any transaction | P1 table |
| P1-9 | Password rules | ≥ 12 characters, ≤ 72 UTF-8 bytes, no composition rules | Length is strength; 72 bytes is bcrypt's limit | P1 table |
| P1-10 | Username case | Any case, lowercased in the service | No case-only duplicates | P1 table |
| (build) P1 | Password checker | One `DaoAuthenticationProvider` bean | Shared rules and events | P1 log §2 |
| (build) P1 | Health endpoint | Public; details admin-only | Probes call it anonymously | P1 log §2 |
| (build) P1 | Login / current-password fields | Only not blank and ≤ 72 bytes | Tightening rules can't lock out old passwords | P1 log §2 |
| P1-11 | Token in email links | URL fragment | Never sent to a server | P1 table |
| P1-14 | Verification link lifetime | 24 h | Mild worst case | P1 table |
| P1-25 | Reset link lifetime | 30 min | A takeover credential | P1 table |
| P2-D1 | Bearer validation | Spring's resource server, own decoder/converter/entry point | Reviewed parsing; custom filter is Phase 10's lesson | P2 table |
| P2-D2 | Signing | RS256, `kid`, ephemeral dev/test key, prod refuses without one | HMAC lets verifiers mint | P2 table |
| P2-D3 | Lifetimes | Access 15 min · refresh 14 d idle · session 30 d absolute | Bounds copies; no endless rotation | P2 table |
| P2-D4 | Refresh transport | `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` cookie | XSS can't read it | P2 table |
| P2-D5 | Access-token revocation | Per-request session check (`sid`, one PK lookup) | Logout and resets act on the next request | P2 table |
| P2-D6 | Reuse detection | Revoke the family + event; 10 s grace | Multi-tab wake-ups don't log users out | P2 table |
| P2-D8 | Session list, revoke one | Built | The lost-laptop case; first IDOR rule | P2 table |
| (build) P2 | `exp` claim | Required by an explicit validator *(your call; the brief missed it)* | A token without `exp` passes the timestamp check | P2 log §2 |

## 5. Account security, recovery and the security log

| ID | Decision | Chosen | Why, in one line | Record |
|---|---|---|---|---|
| P1-17 | What a failed login reveals | Unknown, wrong, locked → the same 401; unverified → 403 after the right password | No account-state oracle | P1 table |
| P1-18 | Lockout | 5 wrong → 15 min; counter resets when locking | Slows guessing; rate limiting is Phase 10 | P1 table |
| (build) P1 | Lockout counter | Event-driven, SQL increment, `REQUIRES_NEW`, fails loudly | Survives the failed login's rollback | P1 log §2 |
| P1-22 | Wrong current password | 400 `CURRENT_PASSWORD_INCORRECT`, counts toward lockout | A 401 logs clients out; counting stops token-holder guessing | P1 table |
| P1-23 | Reset clears lockout | Yes | Mailbox control beats any guess | P1 table |
| P1-24 | Reset verifies the email | Yes | The link proves the address | P1 table |
| P1-26 | Notify after reset/change | Yes, after commit | The owner learns of a takeover | P1 table |
| P2-D7 | Password events and sessions | Reset → all · change → others · lockout → none · everywhere → all | Reset is takeover recovery; lockout revoking would be a DoS | P2 table |
| (build) P3 | Ownership transfer | Sudo mode; checks owner → not yourself → password → transaction | A stolen token can't give an organization away | P3 req §3.2 |
| P1-6 | Login history table | `security_events` with `event_type` | Closes the "audit log has no home" debt | P1 table |
| P1-19 | `security_events` shape | `inet`, fixed types, reason only on failures, no rows for unknown emails, cascade | Valid IPs; erasure erases | P1 table |
| P1-20 | Retention | 12 months (Phase 9 job) | IPs are personal data | P1 table |

## 6. Tenancy and organization authorization

| ID | Decision | Chosen | Why, in one line | Record |
|---|---|---|---|---|
| P3-D5 | Tenant holder | Request-scoped `TenantContext` | No tenant left on a pooled thread | P3 table |
| P3-D6 | Roles | One owner; act on and grant only strictly below; transfer only | No escalation by construction | P3 table |
| P3-D7 | Invitations | Email-bound, no token, accept from "my invitations" | A forwarded link is useless | P3 table |
| P3-D8 | First tenant-owned data | A minimal Project from Phase 4 | Memberships/invitations are access tables | P3 table |
| (build) P3 | The gate | `HandlerInterceptor`, `{orgId}` by name, fails closed, gates only `@Controller` methods | After routing and security; Spring's own `OPTIONS` handler isn't ours | P3 req §3.1 |
| (build) P3 | D6 in code | `mayChangeRole` (member **and** new role below) / `mayRemove` | One rule, tested as full tables | P3 req §3.2 |
| (brief §3.3) | Invitee endpoints | `/users/me/invitations`, outside the gate; `WHERE email = <caller's email>` | No tenant until you accept; someone else's id → 404 | P3 req §3.3 |

## 7. Testing and process

| ID | Decision | Chosen | Why, in one line | Record |
|---|---|---|---|---|
| P1-28 / P2-D17 | Profile (§1.7 → §2.6 → §3.5) | Moved forward twice | First on each trim list | P1, P2 tables |
| P2-D9 | Phase 1 test debt | Deferred *(your call)* | — | P2 table |
| P2-D10, D12, D14, D16 | Phase 2's tests | Skipped / not written *(your calls; D14 against the recommendation)* | Paid in §3.0 (Tier 1) and Phase 11 (Tier 2) | P2 table |
| P3-D1 | Paying the test debt | Tier 1 in §3.0 first; Tier 2 in Phase 11; Phase 3's tests not deferrable | Tests inside time-boxes were cut five times | P3 table |
| (build) P3 | Test clock | Ticking `AdjustableClock` | Tests that don't move time keep real time | P3 log §2 |
| (build) P3 | OpenAPI | Moved to Phase 11 | Documented once, after Phase 4 | P3 log §2 |
| (build) P2 | `CookieSettings` naming in the doc | Kept (deliberate, 2026-10-07) | — | P2 log §2 |

---

## Design rules (principles behind many decisions)

From each implementation reference's Part A, merged where they repeat.

| Rule | Sources |
|---|---|
| **`common` never imports a feature** (interfaces in `common`, implementations in features) | P1 A15 · P2 A10 · P3 A9 · P0-13 |
| **Judge the design on the failure path / by the attacker's copy / by what a member of A gets with B's ids** | P1 A1 · P2 A1 · P3 A1 |
| **Close everything not explicitly opened; fail closed, at startup and at runtime** | P1 A2 · P2 A4 · P3 A5 |
| **No oracle: the same answer for every miss** | P1 A5 · P2 A12 · P3-D3 |
| **The database enforces what code could forget** | P1 A6 · P3 A6 · P1-12 · P1-21 |
| **Decide with the database; read, decide, then write with the value you read** | P2 A6 · P3 A10 |
| **Insert-only entities for rows changed concurrently** | P2 A7 · P3 A8 · P2-D11 |
| **Know when the database actually does something** | P1 A8 |
| **Choose how each method joins a transaction; boundaries are deliberate** | P1 A9 · P2 A9 |
| **Irreversible side effects after commit, from another class** | P1 A10 |
| **Security counters fail loudly** | P1 A11 |
| **Secrets and personal data never reach claims, logs or `toString()`** | P1 A12 · P2 A5 |
| **Time is injected; one reading per operation, truncated to storage precision** | P1 A13 · P2 A8 |
| **The client's IP is the connection's address** | P1 A14 |
| **Sign in with email, record a username** | P1 A3 · P0-9 |
| **The signed-in user is a small immutable principal** | P1 A4 |
| **Normalise once, at the top; the database refuses what slipped through** | P1 A6 |
| **A link token has six properties** | P1 A7 |
| **Two credentials with two jobs; revocation is one row** | P2 A2 · P2 A3 |
| **One error format everywhere** | P2 A11 |
| **Verify framework behaviour in the source, not from memory** | P2 A13 |
| **Two halves of isolation (gate and scope); one source for the current organization; the org in the path, membership per request** | P3 A2 · A3 · A4 |
| **Access tables vs tenant-owned data** | P3 A7 |
| **"Who may act on whom" is one rule, in one place** | P3 A11 |
| **Sudo mode for what a stolen token must not do** | P3 A12 |
