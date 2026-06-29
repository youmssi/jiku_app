-- Invitation delivery tracking (JIKU-16). Tenant-scoped; one row per (guest,
-- channel). status moves PENDING -> SENT / FAILED with a bounded attempt count.
CREATE TABLE invitation (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    event_id   UUID         NOT NULL,
    guest_id   UUID         NOT NULL,
    channel    VARCHAR(32)  NOT NULL,
    status     VARCHAR(32)  NOT NULL,
    attempts   INTEGER      NOT NULL,
    last_error TEXT,
    sent_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_invitation_guest_channel UNIQUE (guest_id, channel)
);

CREATE INDEX idx_invitation_tenant_id ON invitation (tenant_id);
CREATE INDEX idx_invitation_event_id ON invitation (event_id);
