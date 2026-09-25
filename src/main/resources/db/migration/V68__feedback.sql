-- Organizer feedback (JIKU-133): a one-tap rating after a key action, and a
-- message (problem, question, idea, complaint) the platform team answers from
-- the admin desk. Platform-level, like prospect_lead: the team reads across
-- organizations, so the table carries tenant_id for context but is not
-- filtered by the tenant filter.
create table feedback (
    id            uuid primary key,
    kind          varchar(16)  not null,
    moment        varchar(64),
    score         integer check (score between 1 and 5),
    message       text,
    page          varchar(255),
    contact_email varchar(255),
    language      varchar(8),
    tenant_id     varchar(64),
    user_id       varchar(64)  not null,
    status        varchar(16)  not null,
    admin_note    text,
    created_at    timestamptz  not null,
    updated_at    timestamptz
);

create index idx_feedback_kind_created on feedback (kind, created_at desc);
create index idx_feedback_user_created on feedback (user_id, created_at desc);

-- A person is asked about a given moment once: answering again updates the row.
create unique index uq_feedback_rating_moment on feedback (user_id, moment) where kind = 'RATING';
