-- Mobile Money payments (JIKU-33). One row per payment attempt, recorded whether
-- it succeeds or not, for reconciliation and dispute resolution. Tenant-scoped.
-- A successful, provider-confirmed payment unlocks the event's usage tier.
CREATE TABLE payment (
    id                 UUID         PRIMARY KEY,
    tenant_id          VARCHAR(255) NOT NULL,
    event_id           UUID         NOT NULL,
    tier               VARCHAR(64)  NOT NULL,
    amount_minor       BIGINT       NOT NULL,
    currency           VARCHAR(8)   NOT NULL,
    provider           VARCHAR(64)  NOT NULL,
    provider_reference VARCHAR(255),
    status             VARCHAR(32)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_payment_tenant_id ON payment (tenant_id);
CREATE INDEX idx_payment_event_id ON payment (event_id);
