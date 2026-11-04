-- An invitation to join an organization, addressed to an email address (the invitee may not have an account yet).
-- It's accepted by the signed-in user whose verified email matches; there is no token (decision D7).
create table organization_invitations (
    id              bigint       not null,
    organization_id bigint       not null,
    email           varchar(254) not null,
    role            varchar(16)  not null,
    expires_at      timestamptz  not null,
    accepted_at     timestamptz,
    declined_at     timestamptz,
    revoked_at      timestamptz,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    created_by      varchar(50),
    updated_by      varchar(50),

    constraint pk_organization_invitations primary key (id),
    constraint fk_organization_invitations_organization
        foreign key (organization_id) references organizations (id) on delete cascade,
    -- Never OWNER: ownership only moves by transfer.
    constraint ck_organization_invitations_role check (role in ('ADMIN', 'MEMBER')),
    -- Same rules as user_accounts.email, so "invitation email = account email" compares like with like.
    constraint ck_organization_invitations_email_lowercase check (email = lower(email)),
    constraint ck_organization_invitations_email_format check (email ~ '^[^@[:space:]]+@[^@[:space:]]+$'),
    constraint ck_organization_invitations_expiry check (expires_at > created_at),
    -- An invitation ends at most one way: accepted, declined or revoked.
    constraint ck_organization_invitations_single_outcome
        check (num_nonnulls(accepted_at, declined_at, revoked_at) <= 1)
);

-- At most one open invitation per (organization, email). Expiry can't be part of the predicate (now() isn't
-- immutable), so an expired-but-open invitation still holds the slot: re-inviting revokes it first.
create unique index uk_organization_invitations_open
    on organization_invitations (organization_id, email)
    where accepted_at is null and declined_at is null and revoked_at is null;

-- "My invitations": open invitations addressed to the caller's email, across organizations.
create index ix_organization_invitations_open_email
    on organization_invitations (email)
    where accepted_at is null and declined_at is null and revoked_at is null;
