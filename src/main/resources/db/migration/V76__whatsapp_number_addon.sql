-- ADR 105: the "own WhatsApp number" add-on, one row per tenant, extended by
-- each paid period. The Organisation plan and the Organizer Pack include it.
CREATE TABLE whatsapp_number_addon (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_whatsapp_number_addon_tenant UNIQUE (tenant_id)
);
