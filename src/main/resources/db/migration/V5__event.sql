-- Event domain (JIKU-13). Tenant-scoped (tenant_id from BaseTenantEntity). Times
-- are stored as UTC instants; timezone holds the event's IANA zone for display.
CREATE TABLE event (
    id                    UUID         PRIMARY KEY,
    tenant_id             VARCHAR(255) NOT NULL,
    name                  VARCHAR(255) NOT NULL,
    description           TEXT,
    start_date_time       TIMESTAMPTZ,
    end_date_time         TIMESTAMPTZ,
    timezone              VARCHAR(64)  NOT NULL,
    location              VARCHAR(512),
    status                VARCHAR(32)  NOT NULL,
    placement_enabled     BOOLEAN      NOT NULL,
    transfer_allowed      BOOLEAN      NOT NULL,
    transfer_deadline     TIMESTAMPTZ,
    overbooking_allowed   BOOLEAN      NOT NULL,
    max_overbooking_count INTEGER,
    created_at            TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_event_tenant_id ON event (tenant_id);

CREATE TABLE event_invitation_channel (
    event_id UUID        NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    channel  VARCHAR(32) NOT NULL,
    PRIMARY KEY (event_id, channel)
);
