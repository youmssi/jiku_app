-- Accounting-grade invoicing (JIKU-69). A company or public-sector buyer cannot
-- process the plain-text receipt the platform issued until now, which is what
-- made this a hard dependency for corporate events and for the CEMAC market.
--
-- Three tables: the sequential counter that hands out numbers, the invoice
-- header carrying a snapshot of both parties, and its line items.

-- Hands out gapless invoice numbers, one sequence per tenant per fiscal year.
-- Deliberately NOT a PostgreSQL sequence: a sequence keeps advancing when the
-- surrounding transaction rolls back, which produces gaps, and a numbering
-- sequence with holes in it is exactly what a tax audit asks about. The counter
-- is incremented inside the issuing transaction under a row lock, so a rollback
-- returns the number and concurrent issuers serialise.
CREATE TABLE invoice_number_counter (
    id           UUID         PRIMARY KEY,
    tenant_id    VARCHAR(255) NOT NULL,
    fiscal_year  INTEGER      NOT NULL,
    next_number  BIGINT       NOT NULL,
    CONSTRAINT uq_invoice_counter_tenant_year UNIQUE (tenant_id, fiscal_year)
);

CREATE TABLE invoice (
    id                      UUID         PRIMARY KEY,
    tenant_id               VARCHAR(255) NOT NULL,
    -- Human-readable, gapless within (tenant_id, fiscal_year).
    invoice_number          VARCHAR(40)  NOT NULL,
    fiscal_year             INTEGER      NOT NULL,
    sequence_number         BIGINT       NOT NULL,
    document_type           VARCHAR(20)  NOT NULL,
    -- Set only on a credit note: the invoice it corrects. An issued invoice is
    -- never edited, so a correction is a new document pointing back at the old one.
    corrected_invoice_id    UUID,

    -- Buyer, snapshotted from the tenant at issue time so a later change of
    -- address never rewrites a document already sent to an accounts department.
    buyer_legal_name        VARCHAR(255) NOT NULL,
    buyer_registration_number VARCHAR(100),
    buyer_tax_identifier    VARCHAR(100),
    buyer_address_line      VARCHAR(255) NOT NULL,
    buyer_city              VARCHAR(120) NOT NULL,
    buyer_country           VARCHAR(2)   NOT NULL,

    -- Seller, snapshotted for the same reason: the platform's own details change.
    seller_name             VARCHAR(255) NOT NULL,
    seller_address_line     VARCHAR(255),
    seller_tax_identifier   VARCHAR(100),

    currency                VARCHAR(3)   NOT NULL,
    -- All money in minor units, matching the rest of the billing module.
    subtotal_minor          BIGINT       NOT NULL,
    tax_label               VARCHAR(60),
    -- Stored per invoice, never read live: the rate in force at issue time is the
    -- rate the document was computed with, and it must survive a config change.
    tax_rate                NUMERIC(6,4) NOT NULL,
    tax_amount_minor        BIGINT       NOT NULL,
    total_minor             BIGINT       NOT NULL,

    -- Issue date in the buyer's country, not a UTC instant: an invoice is dated by
    -- calendar day in its jurisdiction.
    issue_date              DATE         NOT NULL,
    payment_id              UUID,
    issued_at               TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_invoice_number UNIQUE (tenant_id, fiscal_year, sequence_number),
    CONSTRAINT fk_invoice_corrected FOREIGN KEY (corrected_invoice_id) REFERENCES invoice (id)
);

CREATE INDEX idx_invoice_tenant_id ON invoice (tenant_id);
CREATE INDEX idx_invoice_payment_id ON invoice (payment_id);

CREATE TABLE invoice_line (
    id               UUID         PRIMARY KEY,
    tenant_id        VARCHAR(255) NOT NULL,
    invoice_id       UUID         NOT NULL,
    line_number      INTEGER      NOT NULL,
    description      VARCHAR(500) NOT NULL,
    quantity         BIGINT       NOT NULL,
    unit_price_minor BIGINT       NOT NULL,
    line_total_minor BIGINT       NOT NULL,
    CONSTRAINT fk_invoice_line_invoice FOREIGN KEY (invoice_id) REFERENCES invoice (id)
);

CREATE INDEX idx_invoice_line_invoice_id ON invoice_line (invoice_id);
CREATE INDEX idx_invoice_line_tenant_id ON invoice_line (tenant_id);
