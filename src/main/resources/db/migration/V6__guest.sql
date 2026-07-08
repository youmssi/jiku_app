-- Guest list (JIKU-15). Tenant-scoped (tenant_id from BaseTenantEntity) and scoped
-- to an event. At least one of email / phone_number is present (enforced on import).
CREATE TABLE guest (
    id           UUID         PRIMARY KEY,
    tenant_id    VARCHAR(255) NOT NULL,
    event_id     UUID         NOT NULL,
    first_name   VARCHAR(255) NOT NULL,
    last_name    VARCHAR(255) NOT NULL,
    email        VARCHAR(320),
    phone_number VARCHAR(32),
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_guest_tenant_id ON guest (tenant_id);
CREATE INDEX idx_guest_event_id ON guest (event_id);
