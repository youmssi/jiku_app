-- Rappels de rendez-vous (JIKU-89).
--
-- Par service, un canal et des décalages configurables. Une valeur nulle de
-- reminder_channel (service non configuré, ou canal jamais choisi) signifie
-- « aucun rappel » : le service n'envoie jamais avant d'avoir été activé dans sa
-- configuration. reminder_offsets_minutes stocke les décalages (minutes avant le
-- créneau, du plus grand au plus petit) séparés par des points-virgules, ex.
-- « 1440;120 » pour J-1 et H-2. La colonne garde NULL tant que l'organisateur ne
-- choisit pas ses propres valeurs ; à l'envoi, les décalages par défaut du
-- produit s'appliquent.
ALTER TABLE service_config ADD COLUMN reminder_channel VARCHAR(16);
ALTER TABLE service_config ADD COLUMN reminder_offsets_minutes VARCHAR(120);

-- Un rappel est émis une seule fois par (billet, décalage) : la contrainte
-- d'unicité rend l'insertion du job atomique et idempotente, même si deux
-- instances de l'application balaient en même temps. Le statut suit l'envoi :
-- SENT / QUEUED (retenu par une garde-fou, à rejouer) / FAILED.
CREATE TABLE appointment_reminder (
    id               UUID         PRIMARY KEY,
    tenant_id        VARCHAR(255) NOT NULL,
    ticket_id        UUID         NOT NULL,
    service_id       UUID         NOT NULL,
    offset_minutes   INTEGER      NOT NULL,
    due_at           TIMESTAMPTZ  NOT NULL,
    channel          VARCHAR(16)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    attempts         INTEGER      NOT NULL DEFAULT 0,
    error            VARCHAR(500),
    sent_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_appointment_reminder_ticket_offset UNIQUE (ticket_id, offset_minutes),
    CONSTRAINT fk_appointment_reminder_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id) ON DELETE CASCADE
);

CREATE INDEX idx_appointment_reminder_tenant_id ON appointment_reminder (tenant_id);
CREATE INDEX idx_appointment_reminder_service_id ON appointment_reminder (service_id);
CREATE INDEX idx_appointment_reminder_status ON appointment_reminder (status, created_at);
