-- ADR 105: the WhatsApp appointment reminders a tenant sent each calendar
-- month, counted against a free plan's monthly allowance.
CREATE TABLE whatsapp_reminder_usage (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    month_start DATE NOT NULL,
    sent INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_whatsapp_reminder_usage UNIQUE (tenant_id, month_start)
);
