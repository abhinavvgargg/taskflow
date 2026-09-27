create table user_tokens (
    id bigint not null,
    user_account_id bigint not null,
    purpose varchar(32)   NOT NULL,
    token_hash varchar(64)      NOT NULL,
    expires_at timestamptz   NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    created_by varchar(50),
    updated_by varchar(50),

    constraint pk_user_tokens primary key (id),
    constraint uk_user_tokens_token_hash unique (token_hash),
    constraint fk_user_tokens_user_account foreign key (user_account_id) references user_accounts (id) on delete cascade,
    CONSTRAINT ck_user_tokens_purpose
        CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),

    CONSTRAINT ck_user_tokens_hash_format
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),

    -- expiry should be greater than created
--     CONSTRAINT ck_user_tokens_expiry
--         CHECK (expires_at > created_at),

    -- atleast one of consume or revoked should be null
    CONSTRAINT ck_user_tokens_single_outcome
        CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);

create index ix_user_tokens_account on user_tokens (user_account_id);

-- at most one active token per user and purpose, enforced by the database
create unique index uk_user_tokens_active on user_tokens (user_account_id, purpose) where consumed_at is null and revoked_at is null;