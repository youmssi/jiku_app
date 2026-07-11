-- Time-boxed trial allowances (JIKU-42). Deliberately separate from
-- usage_record.unlocked_allowance, which stays strictly the paid entitlement —
-- the expiry sweep never has to guess whether an allowance was paid for.
CREATE TABLE trial_grant (
    id                 UUID         PRIMARY KEY,
    tenant_id          VARCHAR(255) NOT NULL,
    event_id           UUID         NOT NULL,
    tier               VARCHAR(64)  NOT NULL,
    granted_allowance  BIGINT       NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    expiry_notice_sent BOOLEAN      NOT NULL DEFAULT FALSE,
    ended_reason       TEXT,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_trial_grant_event ON trial_grant (tenant_id, event_id);
-- The sweep scans by status and expiry across tenants.
CREATE INDEX idx_trial_grant_sweep ON trial_grant (status, expires_at);
