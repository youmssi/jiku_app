-- Brevo hybrid email routing (JIKU-62): an atomic, restart-surviving daily
-- counter per provider (RESEND / BREVO / GLOBAL), incremented only through a
-- conditional upsert (see EmailQuotaCounterRepository.tryReserve) so the daily
-- cap is never exceeded even under concurrent sends. Not tenant-scoped — the
-- cap is per provider account, shared by every tenant on the platform transport.
CREATE TABLE email_quota_counter (
    id         UUID        PRIMARY KEY,
    provider   VARCHAR(16) NOT NULL,
    day        DATE        NOT NULL,
    sent_count BIGINT      NOT NULL,
    CONSTRAINT uq_email_quota_counter_provider_day UNIQUE (provider, day)
);
