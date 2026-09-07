-- Compteur de rang du jour par (service, journée locale) (JIKU-88).
--
-- Le rang est séquentiel par service et par jour, alloué sous verrou pessimiste
-- dans la transaction de l'arrivée. La contrainte d'unicité (service_id, day)
-- garde la création : deux premières arrivées de la journée ne créent la ligne
-- qu'une fois, le perdant resélectionne la ligne du gagnant.
CREATE TABLE ticket_day_counter (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    service_id UUID         NOT NULL,
    day        DATE         NOT NULL,
    next_rank  INTEGER      NOT NULL,
    CONSTRAINT fk_day_counter_service FOREIGN KEY (service_id) REFERENCES service (id) ON DELETE CASCADE,
    CONSTRAINT uq_ticket_day_counter_service_day UNIQUE (service_id, day)
);

CREATE INDEX idx_ticket_day_counter_tenant_id ON ticket_day_counter (tenant_id);
