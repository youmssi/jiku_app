-- Operators (JIKU-116): the people who work for an organization without an
-- account — at the door, at the counter, at the till. One operator holds one
-- signed link, a scope of events and services, and the actions it may take.
-- Replaces the per-event validator links (JIKU-23) and the per-service staff
-- links (JIKU-88); their rows become operators with the same ids, so the links
-- already handed out keep working.
CREATE TABLE operator (
    id           UUID         PRIMARY KEY,
    tenant_id    VARCHAR(255) NOT NULL,
    label        VARCHAR(80)  NOT NULL,
    code         VARCHAR(10),
    can_check_in BOOLEAN      NOT NULL DEFAULT FALSE,
    can_queue    BOOLEAN      NOT NULL DEFAULT FALSE,
    can_collect  BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ
);

CREATE INDEX idx_operator_tenant_id ON operator (tenant_id);
CREATE UNIQUE INDEX operator_code_key ON operator (code) WHERE code IS NOT NULL;

CREATE TABLE operator_event (
    operator_id UUID NOT NULL REFERENCES operator (id) ON DELETE CASCADE,
    event_id    UUID NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    PRIMARY KEY (operator_id, event_id)
);

CREATE INDEX idx_operator_event_event_id ON operator_event (event_id);

CREATE TABLE operator_service (
    operator_id UUID NOT NULL REFERENCES operator (id) ON DELETE CASCADE,
    service_id  UUID NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    PRIMARY KEY (operator_id, service_id)
);

CREATE INDEX idx_operator_service_service_id ON operator_service (service_id);

INSERT INTO operator (id, tenant_id, label, can_check_in, can_collect, revoked, created_at, revoked_at)
SELECT id, tenant_id, LEFT(label, 80), TRUE, TRUE, revoked, created_at, revoked_at
FROM validator;

INSERT INTO operator_event (operator_id, event_id)
SELECT v.id, v.event_id
FROM validator v
JOIN event e ON e.id = v.event_id;

INSERT INTO operator (id, tenant_id, label, code, can_queue, can_collect, revoked, created_at, revoked_at)
SELECT id, tenant_id, label, code, TRUE, TRUE, revoked, created_at, revoked_at
FROM service_staff;

INSERT INTO operator_service (operator_id, service_id)
SELECT id, service_id
FROM service_staff;

DROP TABLE validator;
DROP TABLE service_staff;
