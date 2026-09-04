-- Identité du client et de sa réservation (JIKU-87). Une réservation peut occuper
-- plusieurs ressources (exigences multiples) : chaque ligne porte le même
-- booking_token_hash, et la réservation se retrouve et s'annule par ce jeton dans
-- le tenant du service (le jeton lui-même ne contient aucune donnée).
ALTER TABLE service_reservation ADD COLUMN client_name VARCHAR(120);
ALTER TABLE service_reservation ADD COLUMN client_phone VARCHAR(32);
ALTER TABLE service_reservation ADD COLUMN booking_token_hash VARCHAR(64);

CREATE INDEX idx_reservation_booking_token_hash ON service_reservation (booking_token_hash);
