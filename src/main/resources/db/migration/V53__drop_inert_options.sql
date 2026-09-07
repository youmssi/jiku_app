-- Les options « paiement d'un rendez-vous » et « placement de sièges » étaient
-- configurées mais jamais lues par une logique (options inertes, JIKU-B3). Le
-- produit n'encaisse pas les prestations et n'attribue pas de sièges : on retire
-- les réglages plutôt que de laisser des promesses sans effet.
ALTER TABLE service_config DROP COLUMN payment_mode;
ALTER TABLE event DROP COLUMN placement_enabled;
