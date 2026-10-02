-- JIKU-211: every WhatsApp message Meta accepted, keyed by Meta's id, so the
-- delivery statuses Meta reports later (delivered, read, failed) reach the
-- right organization. Not tenant-scoped: the status webhook carries no tenant.
-- sms_fallback holds the text to send by SMS if Meta reports a failure, for
-- messages whose channel allows it; it is cleared once used.
CREATE TABLE whatsapp_message (
    wamid        VARCHAR(128) PRIMARY KEY,
    tenant_id    VARCHAR(255),
    reference_id UUID,
    recipient    VARCHAR(20)  NOT NULL,
    own_number   BOOLEAN      NOT NULL,
    invitation   BOOLEAN      NOT NULL,
    sms_fallback TEXT,
    status       VARCHAR(16)  NOT NULL,
    error_code   INTEGER,
    sent_at      TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_whatsapp_message_tenant ON whatsapp_message (tenant_id, sent_at);
CREATE INDEX idx_whatsapp_message_recipient ON whatsapp_message (recipient, sent_at DESC);

-- Which organization's message a STOP answered, so an organization whose
-- guests keep opting out can be told apart from the others.
ALTER TABLE whatsapp_opt_out ADD COLUMN tenant_id VARCHAR(255);
CREATE INDEX idx_whatsapp_opt_out_tenant ON whatsapp_opt_out (tenant_id, opted_out_at);
