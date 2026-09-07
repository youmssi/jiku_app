-- La ligne du jour (JIKU-88) : le billet de rendez-vous porte le nom et le
-- téléphone du client, pour que l'écran du comptoir s'affiche seul, sans
-- jointure vers l'invité. Nullable : les billets d'invitation n'en ont pas besoin.
ALTER TABLE ticket ADD COLUMN client_name VARCHAR(120);
ALTER TABLE ticket ADD COLUMN client_phone VARCHAR(32);

-- Les recherches de la ligne du jour filtrent par service puis par état ;
-- l'index sert les deux lectures (liste du jour et « suivant »).
CREATE INDEX idx_ticket_service_line ON ticket (service_id, status, starts_at);
