-- Tickets (JIKU-20). Tenant-scoped; one ticket per guest. ticket_code is a
-- cryptographically random, non-sequential value verified against this table at
-- check-in.
CREATE TABLE ticket (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(255) NOT NULL,
    event_id    UUID         NOT NULL,
    guest_id    UUID         NOT NULL,
    ticket_code VARCHAR(64)  NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    issued_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_ticket_guest UNIQUE (guest_id),
    CONSTRAINT uq_ticket_code UNIQUE (ticket_code)
);

CREATE INDEX idx_ticket_tenant_id ON ticket (tenant_id);
CREATE INDEX idx_ticket_event_id ON ticket (event_id);
