-- ADR 105: which organization a WhatsApp business number belongs to, so a
-- guest's message to an organization's own number is answered by it. Outside
-- the tenant filter: the webhook that reads it has no tenant.
CREATE TABLE whatsapp_business_number (
    phone_number_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL
);

CREATE INDEX idx_whatsapp_business_number_tenant ON whatsapp_business_number (tenant_id);
