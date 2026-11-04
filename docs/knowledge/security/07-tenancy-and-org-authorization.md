# Security 7. Tenancy and organization-level authorization

> Phase 3's boundary between customers: who may act in an organization, and who may act on whom inside it. The query side (`@TenantId`) is [jpa 08](../jpa/08-multi-tenancy-with-hibernate.md); the gate's mechanics are [spring 03](../spring/03-filters-interceptors-and-request-scope.md). Sources: [../SOURCES.md](../SOURCES.md).

## What tenancy means here

A **tenant** is a customer whose data no other customer may see; in TaskFlow, an **organization**. Isolation has two halves that fail independently (Phase 3 rule A2):
- **The gate:** may this caller act in organization 42 at all? (§3.1)
- **The scope:** does every query in this request see only organization 42's rows? (§3.4)

The gate without the scope leaks through ids from URLs; the scope without the gate lets anyone pick any organization. **Every section is judged by one question: what does a member of A get when they send B's ids?** The answer is always the 404 they'd get for an id that doesn't exist.

## The decisions (all taken 2026-10-13, the recommendations)

| | Chosen | Why it beats the alternatives |
|---|---|---|
| D2 Tenancy model | Shared schema, `organization_id` on tenant-owned rows | Self-service signup: schema-per-tenant runs DDL per signup and N migrations per deploy; database-per-tenant needs a pool per tenant (500 × 10 connections > `max_connections`) |
| D3 How a request names its organization | In the path, `/organizations/{orgId}/…`; membership and role **read from the database on every request** | A role in the token keeps a removed admin working for 15 minutes; a header hides the tenant from logs and lets a stale tab act in the wrong organization; subdomains need wildcard credentialed CORS; slugs change |
| D4 Isolation | `@TenantId` + the gate + composite FKs (Phase 4); RLS optional (§3.6) | See [jpa 08](../jpa/08-multi-tenancy-with-hibernate.md) |
| D5 Current tenant holder | A request-scoped bean | No tenant left on a pooled thread ([spring 03](../spring/03-filters-interceptors-and-request-scope.md)) |
| D6 Roles | One OWNER; ADMIN; MEMBER. Act on and grant only **strictly below** you; ownership moves only by transfer | "At or below" lets one rogue admin promote an accomplice and remove the others; several owners move the problem to the top |
| D7 Invitations | Bound to the verified email, no token; accepted by id from "my invitations" | A forwarded link can't let a stranger in; login already proves the inbox |
| D8 First tenant-owned data | A minimal Project, pulled from Phase 4 | Memberships and invitations are access tables, read from both sides |

## The gate (§3.1)

`TenantGateInterceptor` on `/api/v1/organizations/*` and `/*/**`: reads `{orgId}` by name (fails closed if missing), takes the caller from the `AuthenticatedUser` principal, looks up the role (one indexed query), and either fills `TenantContext` or answers **404 `ORGANIZATION_NOT_FOUND`** with a constant detail (no id): non-member, non-existent and non-numeric ids are indistinguishable (*proven* by whole-body comparison; §3.1 mutations 2 and 12). It gates every `@Controller` method and nothing else (the `OPTIONS` lesson). `/users/me/invitations` sits outside it on purpose: no tenant exists until you accept.

- **Removal and demotion act on the next request,** because the role is read per request (*seen*: a removed member's next `GET` → 404).
- **Services take the organization only from `TenantContext`,** never a path variable or body field (one source, rule A4).
- **403 only inside:** `ORGANIZATION_ROLE_REQUIRED` (`TenantContext.requireAtLeast`) and `CANNOT_MANAGE_MEMBER` are for members whose role can't do this; they already know the organization exists.

## Who may act on whom (§3.2)

- **One rule, in one place** (rule A11): `OrganizationRole.isAbove` (strict), `mayChangeRole(target, new)` = the member **and** the new role strictly below you, `mayRemove(target)` = the member strictly below you. Tested as complete tables (18 + 9 rows) and a 22-row HTTP matrix for three callers.
- **Two comparisons, not one.** *Hit:* the first version checked only the new role, so the owner could "demote" themselves (OWNER → ADMIN is a role below OWNER); only the database trigger stopped it, as a confusing 409.
- **The matrix that follows:** owners change roles (ADMIN ↔ MEMBER) and remove admins and members; admins remove members; members read. Nobody acts on a peer or themselves; leaving is its own endpoint (`DELETE /members/me`); the owner can't leave (409, transfer first). An admin's same-role "change" is a 200 no-op.
- **Ownership transfer** requires the current password (sudo mode, [02](02-password-authentication.md)), in the order owner → not yourself (400 `CANNOT_TRANSFER_TO_SELF`, since demote-then-promote would return 204 having done nothing) → password → one transaction (demote, then promote).
- **Every change is a conditional statement carrying the role that was read** ([jpa 05](../jpa/05-bulk-updates-and-concurrency.md)); two transfers at once give exactly one owner.
- **Members don't see each other's emails** (data minimisation).

## Invitations (§3.3, brief; being built)

- **Two sides, two keys** (the IDOR rule twice): the organization side (`/organizations/{orgId}/invitations…`, through the gate) always has `organization_id = <TenantContext>` in its `WHERE`; the invitee side (`/users/me/invitations…`) always has `email = <the caller's current email, loaded by id>`. Someone else's invitation id matches nothing → 404.
- **Role can't be `OWNER`** (a `CHECK` and validation) and must be strictly below the inviter (`ROLE_NOT_GRANTABLE`): an invitation can't be a back door to escalation.
- **Emails normalised** like accounts, so "invited address = account address" compares like with like.
- **Expired invitations aren't listed, and accepting one → 404** (one answer for "can't be accepted"; a distinct "expired" would confirm the id).
- **`409 ALREADY_MEMBER` is an accepted email oracle** for managers of that organization ([05](05-account-protection-and-privacy.md)).
- **Accept is one transaction:** a conditional `UPDATE` (1 row or 404), then the membership insert; an existing membership fails on the unique constraint and the invitation stays open.

## Interview questions

1. **How does your API stop a user reading another customer's organization?** A gate before every org-scoped controller looks up the caller's membership per request; no membership → the same 404 as a non-existent id; services read the organization only from the request-scoped context.
2. **Why 404, not 403, for a non-member?** Ids are guessable; 403 maps which organizations exist. 403 is for members whose role is too low.
3. **Why not put the organization and role in the JWT?** A removed or demoted member would keep acting for the token's lifetime; reading per request makes changes take effect immediately.
4. **How do you stop an admin escalating?** One strict rule: act on and grant only roles strictly below yours; nobody can create a peer.
5. **Why do invitations have no token?** The email is the key: the signed-in user with that verified email accepts from "my invitations", so a forwarded link is useless.
