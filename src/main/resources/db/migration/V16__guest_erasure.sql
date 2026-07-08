-- Guest right-to-erasure (JIKU-36). Guests gain an anonymization marker; once
-- erased, their personal identifiers are cleared while the row (and its aggregate
-- contribution) remains. A separate log records every erasure request for the
-- platform's compliance audit trail, even though the personal data is now gone.
ALTER TABLE guest
    ADD COLUMN personal_data_erased BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN erased_at            TIMESTAMPTZ;

CREATE TABLE guest_erasure_log (
    id         UUID         PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    guest_id   UUID         NOT NULL,
    event_id   UUID         NOT NULL,
    reason     VARCHAR(32)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_guest_erasure_log_tenant_id ON guest_erasure_log (tenant_id);
CREATE INDEX idx_guest_erasure_log_guest_id ON guest_erasure_log (guest_id);
