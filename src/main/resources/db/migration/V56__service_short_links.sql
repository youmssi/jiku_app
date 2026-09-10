-- Liens courts de réservation de service : un code lisible remplace le jeton
-- signé dans l'URL partagée (https://jiku.app/r/<code>). Le code est aléatoire
-- et non devinable ; la ligne gouverne la résolution publique, une révocation
-- éventuelle et la suppression en cascade avec le service.
CREATE TABLE service_link (
    id         UUID         PRIMARY KEY,
    code       VARCHAR(12)  NOT NULL,
    tenant_id  VARCHAR(255) NOT NULL,
    service_id UUID         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_service_link_code UNIQUE (code),
    CONSTRAINT fk_service_link_service FOREIGN KEY (service_id) REFERENCES service (id) ON DELETE CASCADE
);

CREATE INDEX idx_service_link_service_id ON service_link (service_id);
