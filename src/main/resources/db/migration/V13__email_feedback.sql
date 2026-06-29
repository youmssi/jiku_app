-- Sender reputation: email delivery feedback (JIKU-28B). One row per bounce or
-- complaint reported by the email provider's webhook. NOT tenant-scoped — the
-- platform-wide reputation job and the cross-tenant undeliverable check read it
-- globally — but it carries the attributed tenant (the tenant of the most recent
-- send to that address) so per-tenant rates can still be computed.
CREATE TABLE email_feedback (
    id            UUID         PRIMARY KEY,
    recipient     VARCHAR(320) NOT NULL,
    feedback_type VARCHAR(32)  NOT NULL,
    tenant_id     VARCHAR(255),
    reference_id  UUID,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_email_feedback_recipient ON email_feedback (recipient);
CREATE INDEX idx_email_feedback_tenant_id ON email_feedback (tenant_id);
CREATE INDEX idx_email_feedback_created_at ON email_feedback (created_at);
