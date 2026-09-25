-- ADR 105: the Organizer Pack. One row per tenant, extended by each paid
-- period; the guests an event day sent past the month's allowance are owed and
-- settled with the next renewal.
CREATE TABLE organizer_pack (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    owed_guests BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_organizer_pack_tenant UNIQUE (tenant_id)
);

-- Each month of a pack: the guests it sent and the extra guests bought for it.
CREATE TABLE organizer_pack_month (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    month_start TIMESTAMP WITH TIME ZONE NOT NULL,
    used_guests BIGINT NOT NULL DEFAULT 0,
    extra_guests BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_organizer_pack_month UNIQUE (tenant_id, month_start)
);

ALTER TABLE payment ADD COLUMN guests BIGINT;
