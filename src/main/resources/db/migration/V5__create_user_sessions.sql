-- One row per login: the "family" every refresh token of that login belongs to.
-- Revoking the session is the single switch that kills its refresh tokens AND its access tokens
-- (the access token carries the session id as the `sid` claim and is checked against this row).
create table user_sessions (
                               id                bigint       not null,
                               user_account_id   bigint       not null,
                               created_at        timestamptz  not null,
                               expires_at        timestamptz  not null,
                               last_refreshed_at timestamptz  not null,
                               revoked_at        timestamptz,
                               revoke_reason     varchar(32),
                               ip_address        inet,
                               user_agent        varchar(512),

                               constraint pk_user_sessions primary key (id),
                               constraint fk_user_sessions_user_account
                                   foreign key (user_account_id) references user_accounts (id) on delete cascade,
    -- Both instants come from one Clock reading in one factory method, so comparing them is safe
    -- (unlike Phase 1's created_at, which the auditor sets from a different clock).
                               constraint ck_user_sessions_expiry check (expires_at > created_at),
                               constraint ck_user_sessions_refreshed_after_created check (last_refreshed_at >= created_at),
                               constraint ck_user_sessions_revoke_reason
                                   check (revoke_reason in ('LOGOUT', 'LOGOUT_ALL', 'REVOKED_BY_USER',
                                                            'PASSWORD_CHANGED', 'PASSWORD_RESET', 'REFRESH_TOKEN_REUSE')),
    -- A revoked session always says why; a live one never has a reason.
                               constraint ck_user_sessions_reason_iff_revoked
                                   check ((revoked_at is null) = (revoke_reason is null))
);

-- Serves the FK cascade, "revoke all sessions of user X" and "list my sessions".
create index ix_user_sessions_user_account on user_sessions (user_account_id);

-- The opaque refresh tokens. Only the SHA-256 hash is stored (same codec as user_tokens).
-- No revoked_at here on purpose: revocation lives on the session, in one place.
create table refresh_tokens (
                                id          bigint       not null,
                                session_id  bigint       not null,
                                token_hash  varchar(64)  not null,
                                created_at  timestamptz  not null,
                                expires_at  timestamptz  not null,
                                consumed_at timestamptz,

                                constraint pk_refresh_tokens primary key (id),
                                constraint uk_refresh_tokens_token_hash unique (token_hash),
                                constraint fk_refresh_tokens_session
                                    foreign key (session_id) references user_sessions (id) on delete cascade,
                                constraint ck_refresh_tokens_hash_format check (token_hash ~ '^[0-9a-f]{64}$'),
                                constraint ck_refresh_tokens_expiry check (expires_at > created_at),
                                constraint ck_refresh_tokens_consumed_after_created check (consumed_at is null or consumed_at >= created_at)
);

-- Serves the FK cascade (must include consumed rows, so it can't be the partial index below).
create index ix_refresh_tokens_session on refresh_tokens (session_id);

-- At most one unconsumed token per session, enforced by the database:
-- a rotation bug or a race can never leave two live tokens in one family.
create unique index uk_refresh_tokens_active on refresh_tokens (session_id) where consumed_at is null;
