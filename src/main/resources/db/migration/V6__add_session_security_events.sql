-- V4 is applied and frozen: the allowed event types change by replacing the constraint, forward only.
alter table security_events drop constraint ck_security_events_event_type;

alter table security_events add constraint ck_security_events_event_type
    check (event_type in ('LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'ACCOUNT_LOCKED',
                          'EMAIL_VERIFIED', 'PASSWORD_RESET', 'PASSWORD_CHANGED',
                          'SESSION_REVOKED', 'ALL_SESSIONS_REVOKED', 'REFRESH_TOKEN_REUSED'));
