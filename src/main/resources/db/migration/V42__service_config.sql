-- Configuration par service (JIKU-86). Toutes les options du §5 sont persistées
-- ici, en colonnes nulles : une valeur nulle signifie « appliquer le défaut de
-- configuration » (catalog.slot.* pour la grille, catalog.service.* pour le
-- reste). Un service créé sans configuration est donc utilisable tel quel.
CREATE TABLE service_config (
    service_id                 UUID         PRIMARY KEY,
    tenant_id                  VARCHAR(255) NOT NULL,
    confirmation_mode          VARCHAR(32),
    step_minutes               INTEGER,
    duration_minutes           INTEGER,
    buffer_minutes             INTEGER,
    min_horizon_minutes        INTEGER,
    max_horizon_days           INTEGER,
    hold_minutes               BIGINT,
    cancel_deadline_hours      INTEGER,
    no_show_tolerance_minutes  INTEGER,
    walk_ins_allowed           BOOLEAN,
    payment_mode               VARCHAR(32),
    CONSTRAINT fk_service_config_service FOREIGN KEY (service_id) REFERENCES service (id) ON DELETE CASCADE
);

CREATE INDEX idx_service_config_tenant_id ON service_config (tenant_id);
