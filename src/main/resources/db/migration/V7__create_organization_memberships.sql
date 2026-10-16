-- Organizations become tenants: one row per (organization, user), with the user's role in that organization.
-- Every request under /api/v1/organizations/{orgId}/... is checked against this table (one indexed lookup).
create table organization_memberships (
    id              bigint      not null,
    organization_id bigint      not null,
    user_account_id bigint      not null,
    role            varchar(16) not null,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    created_by      varchar(50),
    updated_by      varchar(50),

    constraint pk_organization_memberships primary key (id),
    constraint fk_organization_memberships_organization
        foreign key (organization_id) references organizations (id) on delete cascade,
    constraint fk_organization_memberships_user_account
        foreign key (user_account_id) references user_accounts (id) on delete cascade,
    -- One membership per user per organization. Its index serves the per-request lookup (organization_id, user_account_id),
    -- and it's the target later tables reference with a composite FK ("this user is a member of THIS organization").
    constraint uk_organization_memberships_org_user unique (organization_id, user_account_id),
    constraint ck_organization_memberships_role check (role in ('OWNER', 'ADMIN', 'MEMBER'))
);

-- "My organizations" looks memberships up by user; the unique index above leads with organization_id and can't serve it.
create index ix_organization_memberships_user on organization_memberships (user_account_id);

-- At most one owner per organization. A unique index is checked row by row, immediately:
-- an ownership transfer must demote the old owner BEFORE it promotes the new one.
create unique index uk_organization_memberships_one_owner
    on organization_memberships (organization_id) where role = 'OWNER';

-- At least one owner per organization, checked at COMMIT (deferred): a transaction may pass through "no owner"
-- (between the two updates of a transfer) but can never commit it.
create function organization_has_owner_check() returns trigger
    language plpgsql as $$
declare
    org_id bigint;
begin
    if tg_table_name = 'organizations' then
        org_id := new.id;
    elsif tg_op = 'DELETE' then
        org_id := old.organization_id;
    else
        org_id := new.organization_id;
    end if;

    -- A deleted organization needs no owner (its memberships cascade away with it).
    if exists (select 1 from organizations where id = org_id)
       and not exists (select 1 from organization_memberships where organization_id = org_id and role = 'OWNER') then
        -- Worded like PostgreSQL's own message, so Hibernate extracts the name and the API's 409 carries it.
        raise exception 'organization % violates check constraint "ck_organizations_has_owner"', org_id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

-- A new organization must have its owner by the end of the transaction that creates it.
create constraint trigger trg_organizations_has_owner
    after insert on organizations
    deferrable initially deferred
    for each row execute function organization_has_owner_check();

-- Changing or removing a membership must not leave its organization without an owner.
create constraint trigger trg_organization_memberships_has_owner
    after update or delete on organization_memberships
    deferrable initially deferred
    for each row execute function organization_has_owner_check();

-- Existing organizations: the creator becomes the owner where created_by names a real user (created_by has held the
-- app username since Phase 1). Organizations created anonymously ('system') stay ownerless: no request can reach them.
insert into organization_memberships (id, organization_id, user_account_id, role, created_at, updated_at, created_by, updated_by)
select nextval('global_id_seq'), o.id, u.id, 'OWNER', now(), now(), 'migration', 'migration'
from organizations o
join user_accounts u on u.username = o.created_by;
