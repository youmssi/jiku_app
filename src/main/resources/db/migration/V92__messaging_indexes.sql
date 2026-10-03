-- JIKU-216: indexes for the counts every send and every reputation check runs.

-- One organization's sends on one channel since a time (email and WhatsApp
-- pauses, usage). Its prefix replaces the tenant-only index.
CREATE INDEX idx_notification_log_tenant_sent ON notification_log (tenant_id, status, channel, created_at);
DROP INDEX idx_notification_log_tenant_id;
-- Platform-wide counts over a period (reputation, spend alert) and the purge.
CREATE INDEX idx_notification_log_created_at ON notification_log (created_at);
-- The organization that last wrote to an address, for a bounce or a complaint.
CREATE INDEX idx_notification_log_recipient_sent ON notification_log (recipient, created_at DESC) WHERE status = 'SENT';

-- One organization's bounces since a time (email pause); prefix replaces the tenant-only index.
CREATE INDEX idx_email_feedback_tenant_type ON email_feedback (tenant_id, feedback_type, created_at);
DROP INDEX idx_email_feedback_tenant_id;
-- Hard bounces of one address (undeliverable check); prefix replaces the recipient-only index.
CREATE INDEX idx_email_feedback_recipient_type ON email_feedback (recipient, feedback_type);
DROP INDEX idx_email_feedback_recipient;

-- The purge removes old rows by date.
CREATE INDEX idx_whatsapp_message_sent_at ON whatsapp_message (sent_at);
CREATE INDEX idx_whatsapp_thread_sent_at ON whatsapp_thread (sent_at);
