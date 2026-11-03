-- JIKU-281: an invitation the organizer sent from their own WhatsApp, with a
-- personal link Jikū prepared, rather than one Jikū delivered.
ALTER TABLE invitation ADD COLUMN sent_by_organizer BOOLEAN NOT NULL DEFAULT FALSE;
