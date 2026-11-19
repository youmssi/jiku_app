-- Guided questions on a service (JIKU-191): at most three choice questions asked
-- before the times, each with two to ten answers short enough for WhatsApp
-- buttons and lists. An answer can send the client to another service.
CREATE TABLE service_question (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(255) NOT NULL,
    service_id  UUID         NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    position    INT          NOT NULL,
    prompt      VARCHAR(140) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_service_question_service ON service_question (service_id, position);

CREATE TABLE service_question_choice (
    id               UUID         PRIMARY KEY,
    tenant_id        VARCHAR(255) NOT NULL,
    question_id      UUID         NOT NULL REFERENCES service_question (id) ON DELETE CASCADE,
    position         INT          NOT NULL,
    label            VARCHAR(20)  NOT NULL,
    route_service_id UUID         REFERENCES service (id) ON DELETE SET NULL
);
CREATE INDEX idx_service_question_choice_question ON service_question_choice (question_id, position);

-- What a client answered, copied as text when they book: editing or removing a
-- question later never changes what an appointment recorded. Kept per booking
-- (one booking can hold several reservation rows) and erased with the client's
-- details (JIKU-36, JIKU-37).
CREATE TABLE service_booking_answer (
    id                 UUID         PRIMARY KEY,
    tenant_id          VARCHAR(255) NOT NULL,
    service_id         UUID         NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    booking_token_hash VARCHAR(64)  NOT NULL,
    starts_at          TIMESTAMPTZ  NOT NULL,
    position           INT          NOT NULL,
    question           VARCHAR(140) NOT NULL,
    answer             VARCHAR(20)  NOT NULL
);
CREATE INDEX idx_service_booking_answer_booking ON service_booking_answer (booking_token_hash);
CREATE INDEX idx_service_booking_answer_service ON service_booking_answer (service_id, starts_at);
