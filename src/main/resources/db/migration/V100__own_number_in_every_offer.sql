-- JIKU-271: every offer may send from the organization's own WhatsApp number
-- (ADR 109, decision 11), so the monthly add-on that sold it goes. A request
-- for it still waiting for payment is closed as failed; none was ever paid.
UPDATE payment SET status = 'FAILED', updated_at = now() WHERE kind = 'WHATSAPP_NUMBER' AND status = 'PENDING';
DROP TABLE whatsapp_number_addon;
