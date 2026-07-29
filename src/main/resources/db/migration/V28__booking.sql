-- Deposit-reservation bookings (JIKU-55). Deliberately not tenant-scoped: a
-- prospect has no tenant until their deposit is verified and one is
-- provisioned, backfilling tenant_id/event_id.
CREATE TABLE booking (
    id                    UUID          PRIMARY KEY,
    customer_name         VARCHAR(255)  NOT NULL,
    customer_phone        VARCHAR(64)   NOT NULL,
    customer_email        VARCHAR(255)  NOT NULL,
    event_type            VARCHAR(32)   NOT NULL,
    event_date            DATE          NOT NULL,
    guest_count_estimate  BIGINT        NOT NULL,
    tier                  VARCHAR(32)   NOT NULL,
    total_amount_minor    BIGINT        NOT NULL,
    deposit_rate          NUMERIC(4,3)  NOT NULL,
    deposit_amount_minor  BIGINT        NOT NULL,
    balance_amount_minor  BIGINT        NOT NULL,
    balance_due_date      DATE          NOT NULL,
    access_token_hash     VARCHAR(128)  NOT NULL,
    status                VARCHAR(32)   NOT NULL,
    tenant_id             VARCHAR(255),
    event_id              UUID,
    acquisition_source    VARCHAR(255),
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_booking_access_token UNIQUE (access_token_hash)
);

CREATE INDEX idx_booking_status ON booking (status);
CREATE INDEX idx_booking_tenant_id ON booking (tenant_id);

-- One customer's claimed Mobile Money transfer against a booking. A partial
-- unique index (rather than a plain UNIQUE column) lets a genuinely reused
-- reference still be inserted and marked DUPLICATE for the audit trail and the
-- admin fraud alert, while guaranteeing at most one non-duplicate row per
-- reference platform-wide.
CREATE TABLE booking_payment_declaration (
    id                   UUID         PRIMARY KEY,
    booking_id           UUID         NOT NULL REFERENCES booking (id) ON DELETE CASCADE,
    amount_minor         BIGINT       NOT NULL,
    kind                 VARCHAR(16)  NOT NULL,
    operator             VARCHAR(32)  NOT NULL,
    transaction_reference VARCHAR(128) NOT NULL,
    declared_at          TIMESTAMPTZ  NOT NULL,
    verification_status  VARCHAR(16)  NOT NULL,
    verified_by          VARCHAR(255),
    verified_at          TIMESTAMPTZ,
    rejection_reason     TEXT
);

CREATE UNIQUE INDEX uq_booking_payment_declaration_reference
    ON booking_payment_declaration (transaction_reference)
    WHERE verification_status <> 'DUPLICATE';

CREATE INDEX idx_booking_payment_declaration_booking_id ON booking_payment_declaration (booking_id);
CREATE INDEX idx_booking_payment_declaration_status ON booking_payment_declaration (verification_status);
