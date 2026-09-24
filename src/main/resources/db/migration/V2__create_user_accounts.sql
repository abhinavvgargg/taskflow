create table user_accounts (
    id bigint not null,
    email varchar(254) not null,
    username varchar(50) not null,
    display_name varchar(100) not null,
    timezone varchar(64) not null default 'UTC',
    -- {id}-prefixed hash. bcrypt is 68 chars with its prefix; sized for the longest encoder we could migrate to
    -- ({pbkdf2@SpringSecurity_v5_8} ~124, {argon2} ~105), not for bcrypt alone.
    password_hash varchar(200) not null,
    role varchar(20) not null default 'USER',
    email_verified_at timestamptz,
    failed_login_attempts integer not null default 0,
    locked_until timestamptz,
    password_changed_at timestamptz not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    created_by varchar(50),
    updated_by varchar(50),

    constraint pk_user_accounts primary key (id),
    constraint uk_user_accounts_email unique (email),
    constraint uk_user_accounts_username unique (username),
    -- The application normalises emails (trim + lowercase); this catches any path that forgot to.
    constraint ck_user_accounts_email_lowercase check (email = lower(email)),
    constraint ck_user_accounts_email_format check (email ~ '^[^@[:space:]]+@[^@[:space:]]+$'),
    -- 3-50 chars, lowercase letters/digits with . _ - inside. No '@', so a username can never equal someone's email.
    constraint ck_user_accounts_username_format check (username ~ '^[a-z0-9][a-z0-9._-]{1,48}[a-z0-9]$'),
    constraint ck_user_accounts_display_name_not_blank check (btrim(display_name) <> ''),
    -- A hash without its {id} prefix can't be verified by DelegatingPasswordEncoder; refuse it at the door.
    constraint ck_user_accounts_password_hash_prefixed check (password_hash ~ '^\{[^}]+\}.+$'),
    constraint ck_user_accounts_role check (role in ('USER', 'ADMIN')),
    constraint ck_user_accounts_failed_login_attempts check (failed_login_attempts >= 0)
);
