create sequence global_id_seq start with 1 increment by 50;

create table organizations (
    id bigint not null,
    name varchar(100) not null,
    slug varchar(63) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    created_by varchar(50),
    updated_by varchar(50),

    constraint pk_organizations primary key (id),
    constraint uk_organizations_slug unique (slug),
    constraint ck_organizations_slug_format check (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);