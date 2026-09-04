-- Les titulaires de rendez-vous (JIKU-87) ne sont pas des invités d'événement :
-- ils n'appartiennent à aucun événement. event_id devient nullable sur guest,
-- ticket et guest_erasure_log pour accueillir ces billets. Purement additif : les
-- lignes existantes gardent leur valeur, et les requêtes scopées par event_id
-- ignorent naturellement les lignes sans événement.
ALTER TABLE guest ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE ticket ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE guest_erasure_log ALTER COLUMN event_id DROP NOT NULL;
