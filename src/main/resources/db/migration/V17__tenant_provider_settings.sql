-- Per-tenant messaging provider credentials (JIKU-44). One row per tenant and
-- channel; when present and active it overrides the platform-level transport
-- for that tenant's sends. Credentials are encrypted at the application layer
-- (AES-GCM) before they reach this column — never stored in clear.
CREATE TABLE tenant_provider_settings (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(255) NOT NULL,
    channel     VARCHAR(16)  NOT NULL,
    provider    VARCHAR(32)  NOT NULL,
    credentials TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_tenant_provider_channel UNIQUE (tenant_id, channel)
);

CREATE INDEX idx_tenant_provider_settings_tenant_id ON tenant_provider_settings (tenant_id);
