-- JIKU-143: routing WhatsApp replies back to the invitation they answer. A
-- reply reaches one platform webhook with no tenant attached, so this table is
-- deliberately not tenant-scoped: it maps a sent invitation to its tenant and
-- the number it went to, and nothing else.
CREATE TABLE whatsapp_thread (
    invitation_id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    language VARCHAR(8) NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_whatsapp_thread_phone ON whatsapp_thread (phone, sent_at DESC);

-- Numbers that wrote STOP: nothing more is sent to them by WhatsApp until they
-- write START.
CREATE TABLE whatsapp_opt_out (
    phone VARCHAR(20) PRIMARY KEY,
    opted_out_at TIMESTAMP WITH TIME ZONE NOT NULL
);
