-- Service, exigences en ressources et réservation de créneaux (JIKU-85).
--
-- Un service est tenant-scopé (coupe, coloration…). Ses exigences déclarent, par
-- type de ressource, la quantité requise pour servir un client. La réservation
-- occupe chaque ressource affectée sur [starts_at, ends_at) ; l'unicité
-- (resource_id, starts_at) est la garde de concurrence : exactement un INSERT
-- conditionnel aboutit pour une case donnée.
CREATE TABLE service (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    name       VARCHAR(120) NOT NULL,
    timezone   VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_service_tenant_id ON service (tenant_id);

CREATE TABLE service_requirement (
    id            UUID         PRIMARY KEY,
    tenant_id     VARCHAR(255) NOT NULL,
    service_id    UUID         NOT NULL,
    resource_type VARCHAR(32)  NOT NULL,
    quantity      INTEGER      NOT NULL,
    CONSTRAINT fk_requirement_service FOREIGN KEY (service_id) REFERENCES service (id) ON DELETE CASCADE,
    CONSTRAINT chk_requirement_quantity CHECK (quantity > 0),
    CONSTRAINT uq_service_requirement_type UNIQUE (service_id, resource_type)
);

CREATE INDEX idx_requirement_tenant_id ON service_requirement (tenant_id);
CREATE INDEX idx_requirement_service_id ON service_requirement (service_id);

CREATE TABLE service_reservation (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(255) NOT NULL,
    service_id  UUID         NOT NULL,
    resource_id UUID         NOT NULL,
    starts_at   TIMESTAMPTZ  NOT NULL,
    ends_at     TIMESTAMPTZ  NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    held_until  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_reservation_service FOREIGN KEY (service_id) REFERENCES service (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservation_resource FOREIGN KEY (resource_id) REFERENCES resource (id) ON DELETE CASCADE,
    CONSTRAINT chk_reservation_bounds CHECK (ends_at > starts_at),
    CONSTRAINT uq_reservation_resource_start UNIQUE (resource_id, starts_at)
);

CREATE INDEX idx_reservation_tenant_id ON service_reservation (tenant_id);
CREATE INDEX idx_reservation_service_id ON service_reservation (service_id);
CREATE INDEX idx_reservation_resource_id ON service_reservation (resource_id);
