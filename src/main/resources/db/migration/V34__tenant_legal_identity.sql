-- Legal identity of the organization, for accounting-grade invoicing (JIKU-69).
-- A family paying by Mobile Money needs none of this; a company or public-sector
-- buyer cannot process an invoice without it, which is what makes it the hard
-- dependency for corporate events and for the CEMAC market.
--
-- Every column is nullable: an organization supplies these only when it needs a
-- compliant invoice, and the invoice service refuses to issue one until they are
-- present rather than emitting a document with blanks where the buyer should be.
--
-- These are the tenant's *current* details. They are snapshotted onto each invoice
-- at issue time (see V35), so correcting an address later never rewrites a
-- document already sent to an accounts department.
ALTER TABLE tenant
    ADD COLUMN legal_name           VARCHAR(255),
    ADD COLUMN registration_number  VARCHAR(100),
    ADD COLUMN tax_identifier       VARCHAR(100),
    ADD COLUMN legal_address_line    VARCHAR(255),
    ADD COLUMN legal_city           VARCHAR(120),
    ADD COLUMN legal_country        VARCHAR(2);
