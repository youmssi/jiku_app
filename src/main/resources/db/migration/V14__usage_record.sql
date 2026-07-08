-- Per-event usage metering (JIKU-32), the auditable basis for billing. One row
-- per (tenant, event). The count columns are a snapshot of usage derived from the
-- invitation module; unlocked_allowance is the entitlement (free tier by default,
-- raised by a paid unlock in JIKU-33). Tenant-scoped.
CREATE TABLE usage_record (
    id                        UUID         PRIMARY KEY,
    tenant_id                 VARCHAR(255) NOT NULL,
    event_id                  UUID         NOT NULL,
    guests_imported           BIGINT       NOT NULL DEFAULT 0,
    invited_guests            BIGINT       NOT NULL DEFAULT 0,
    invitations_sent_email    BIGINT       NOT NULL DEFAULT 0,
    invitations_sent_whatsapp BIGINT       NOT NULL DEFAULT 0,
    unlocked_allowance        BIGINT       NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_usage_record_event UNIQUE (tenant_id, event_id)
);

CREATE INDEX idx_usage_record_tenant_id ON usage_record (tenant_id);
CREATE INDEX idx_usage_record_event_id ON usage_record (event_id);
