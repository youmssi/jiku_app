-- JIKU-293 : la langue dans laquelle les invités d'un événement lisent la carte,
-- le billet et les messages (fr, en ou ar). NULL : la langue de l'organisation.
ALTER TABLE event ADD COLUMN guest_language VARCHAR(2);
ALTER TABLE event ADD CONSTRAINT event_guest_language_check CHECK (guest_language IN ('fr', 'en', 'ar'));
