create table security_events (
                                 id              bigint       not null,
                                 user_account_id bigint       not null,
                                 event_type      varchar(32)  not null,
                                 failure_reason  varchar(32),
                                 occurred_at     timestamptz  not null,
                                 ip_address      inet,
                                 user_agent      varchar(512),

                                 constraint pk_security_events primary key (id),
                                 constraint fk_security_events_user_account
                                     foreign key (user_account_id) references user_accounts (id) on delete cascade,
                                 constraint ck_security_events_event_type
                                     check (event_type in ('LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'ACCOUNT_LOCKED',
                                                           'EMAIL_VERIFIED', 'PASSWORD_RESET', 'PASSWORD_CHANGED')),
                                 constraint ck_security_events_failure_reason
                                     check (failure_reason in ('BAD_CREDENTIALS', 'ACCOUNT_LOCKED', 'EMAIL_NOT_VERIFIED')),
                                 constraint ck_security_events_reason_iff_login_failed
                                     check ((event_type = 'LOGIN_FAILED') = (failure_reason is not null))
);

-- Serves the history query (newest first) and the FK: its leading column is user_account_id.
create index ix_security_events_user_account_occurred
    on security_events (user_account_id, occurred_at, id);

-- Append-only, enforced for every writer. DELETE stays allowed (cascade, retention purge).
create function security_events_reject_update() returns trigger
    language plpgsql as $$
begin
    raise exception 'security_events is append-only: UPDATE is not allowed';
end;
$$;

create trigger trg_security_events_append_only
    before update on security_events
    for each row execute function security_events_reject_update();