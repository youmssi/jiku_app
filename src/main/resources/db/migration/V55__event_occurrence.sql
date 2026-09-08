-- Occurrence d'un événement multi-dates / récurrent (ADR 103) : une date du
-- programme, avec sa propre capacité « salle ». La facturation reste unique à la
-- racine (l'union des invités distincts). Tenant-scopée (colonne @TenantId).
CREATE TABLE event_occurrence (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(36) NOT NULL,
    event_id    UUID        NOT NULL,
    starts_at   TIMESTAMPTZ NOT NULL,
    ends_at     TIMESTAMPTZ,
    capacity    INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_event_occurrence_event ON event_occurrence (event_id, starts_at);
