-- Lien du personnel vers la console de ligne du jour d'un service (JIKU-88).
-- Calqué sur le patron Validator : la ligne est un jeton signé, cette table
-- tenant-scopée gouverne la révocation immédiate. Un membre du personnel n'a pas
-- de compte ; il agit par le lien partagé par l'organisateur.
CREATE TABLE service_staff (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    service_id UUID         NOT NULL,
    label      VARCHAR(80)  NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT fk_staff_service FOREIGN KEY (service_id) REFERENCES service (id) ON DELETE CASCADE
);

CREATE INDEX idx_service_staff_tenant_id ON service_staff (tenant_id);
CREATE INDEX idx_service_staff_service_id ON service_staff (service_id);
