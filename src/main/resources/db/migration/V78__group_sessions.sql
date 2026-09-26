-- Séances collectives (JIKU-174) : une ressource peut recevoir plusieurs clients
-- sur le même créneau, jusqu'à la capacité du service, plafonnée par l'offre.
-- Chaque client occupe une place (seat) ; l'unicité (ressource, début, place)
-- reste la garde de concurrence : deux clients ne prennent jamais la même place.

ALTER TABLE service_reservation ADD COLUMN seat INT NOT NULL DEFAULT 0;
ALTER TABLE service_reservation DROP CONSTRAINT uq_reservation_resource_start;
ALTER TABLE service_reservation
    ADD CONSTRAINT uq_reservation_resource_start_seat UNIQUE (resource_id, starts_at, seat);
ALTER TABLE service_reservation ADD CONSTRAINT chk_reservation_seat CHECK (seat >= 0);

ALTER TABLE service_config ADD COLUMN clients_per_slot INT;
ALTER TABLE service_config ADD CONSTRAINT chk_service_config_clients_per_slot CHECK (clients_per_slot >= 1);
