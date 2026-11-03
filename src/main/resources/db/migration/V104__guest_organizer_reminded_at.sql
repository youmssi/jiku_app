-- JIKU-290 : la dernière relance que l'organisateur a envoyée lui-même, depuis
-- son WhatsApp, à un invité sans réponse.
ALTER TABLE guest ADD COLUMN organizer_reminded_at TIMESTAMPTZ;
