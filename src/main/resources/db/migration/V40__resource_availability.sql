-- Ressources et leur disponibilité (JIKU-84).
--
-- Une ressource (personne, lieu, équipement) est tenant-scopée comme le reste.
-- Sa disponibilité est hebdomadaire, exprimée en heures locales au fuseau de la
-- ressource (day_of_week ISO-8601, 1 = lundi). Une indisponibilité couvre une
-- période en UTC et prime sur l'horaire. Rien ici n'est consommé par le moteur de
-- créneaux tant que JIKU-85 n'existe pas ; la désactivation (active = false) ne
-- bloque aucun créneau déjà pris.
CREATE TABLE resource (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    name       VARCHAR(120) NOT NULL,
    type       VARCHAR(32)  NOT NULL,
    timezone   VARCHAR(64)  NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_resource_tenant_id ON resource (tenant_id);

CREATE TABLE resource_availability (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(255) NOT NULL,
    resource_id UUID         NOT NULL,
    day_of_week INTEGER      NOT NULL,
    start_time  TIME         NOT NULL,
    end_time    TIME         NOT NULL,
    CONSTRAINT fk_availability_resource FOREIGN KEY (resource_id) REFERENCES resource (id) ON DELETE CASCADE,
    CONSTRAINT chk_availability_day CHECK (day_of_week BETWEEN 1 AND 7)
);

CREATE INDEX idx_availability_tenant_id ON resource_availability (tenant_id);
CREATE INDEX idx_availability_resource_id ON resource_availability (resource_id);

CREATE TABLE resource_unavailability (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(255) NOT NULL,
    resource_id UUID         NOT NULL,
    starts_at   TIMESTAMPTZ  NOT NULL,
    ends_at     TIMESTAMPTZ  NOT NULL,
    reason      VARCHAR(255),
    CONSTRAINT fk_unavailability_resource FOREIGN KEY (resource_id) REFERENCES resource (id) ON DELETE CASCADE,
    CONSTRAINT chk_unavailability_bounds CHECK (ends_at > starts_at)
);

CREATE INDEX idx_unavailability_tenant_id ON resource_unavailability (tenant_id);
CREATE INDEX idx_unavailability_resource_id ON resource_unavailability (resource_id);
