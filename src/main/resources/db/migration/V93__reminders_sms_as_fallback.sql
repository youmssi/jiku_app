-- JIKU-244: SMS is a fallback, no longer a channel of its own. A service that
-- chose SMS-only reminders now tries WhatsApp first and falls back to SMS,
-- which still reaches clients without WhatsApp.
UPDATE service_config SET reminder_channel = 'WHATSAPP_OR_SMS' WHERE reminder_channel = 'SMS';
