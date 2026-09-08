-- Question personnalisée posée à l'invité au moment de répondre (JIKU-77) :
-- menu, transport, table… Tenant-scopée (colonne gérée par Hibernate @TenantId).
CREATE TABLE event_question (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(36) NOT NULL,
    event_id    UUID        NOT NULL,
    prompt      VARCHAR(500) NOT NULL,
    required    BOOLEAN     NOT NULL DEFAULT FALSE,
    position    INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_event_question_event ON event_question (event_id, position);
