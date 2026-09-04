-- Le billet de rendez-vous (JIKU-87) porte le service et le professionnel, pour
-- être affiché seul (créneau, lieu, nom) sans jointure. Nullable : les billets
-- d'invitation n'en ont pas besoin.
ALTER TABLE ticket ADD COLUMN service_id UUID;
ALTER TABLE ticket ADD COLUMN professional_name VARCHAR(120);

CREATE INDEX idx_ticket_service_id ON ticket (service_id);
